package forge.llm.openai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.tinylog.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.llm.agent.AgentChoice;
import forge.llm.agent.AgentRequest;
import forge.llm.agent.ChoiceParser;
import forge.llm.agent.ContextOverflowException;
import forge.llm.agent.ModelAgent;
import forge.llm.agent.SummaryRequest;
import forge.llm.mcp.McpCardLookup;
import forge.llm.prompt.SystemPrompt;

/**
 * A {@link ModelAgent} that talks to a raw OpenAI-compatible <em>Responses</em> API (tested against
 * OpenRouter's, see its /docs/api_reference/responses) without opencode in between. The endpoint and the
 * API key come from the environment only ({@link RawInferenceSettings#ENDPOINT_ENV} /
 * {@link RawInferenceSettings#API_KEY_ENV}).
 *
 * <ul>
 *   <li>The Responses API is stateless, so the conversation is kept here: every request resends the whole
 *       history of its session (user prompts, assistant replies, tool calls and their outputs).</li>
 *   <li>If a card server is configured, the model gets a {@code lookupCard} function tool; this class
 *       executes the calls through MCP (see forge-llm/mcp) and feeds the results back.</li>
 *   <li>The model's reply is parsed with {@link ChoiceParser}; an unreadable reply is answered once with a
 *       short reminder of the format before it is given up on.</li>
 *   <li>The conversation size is tracked from the reported token counts. A request that would not fit the
 *       model's context window is not sent; {@link ContextOverflowException} tells the controller to start
 *       over with a compact prompt.</li>
 * </ul>
 */
public final class RawOpenAiAgent implements ModelAgent {
    /** Conservative characters-per-token for English prose plus card text and digits. */
    private static final double CHARS_PER_TOKEN = 3.2;
    /** What this client adds on top of the conversation: the instructions, the answer format and, at most, the tool. */
    private static final int REQUEST_OVERHEAD_TOKENS = 800;
    private static final int LOOKUP_TOOL_TOKENS = 700;
    /** Used when neither --oc-context nor a known window sets it. */
    private static final int DEFAULT_CONTEXT = 131_072;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    /** The tool name the model may call; the agent executes it against the card MCP server. */
    static final String TOOL_LOOKUP_CARD = "lookupCard";

    static final String FORMAT_REMINDER = """
            Your reply could not be understood. Reply with exactly one line of the form
              ACTION <id>        (or ACTIONS <id>, <id> for several, or PASS, or NONE)
            followed, if you like, by a line  REASON: <short reason>.
            Use only ids from the AVAILABLE ACTIONS list above.""";

    private static final String LOOKUP_ADDENDUM = """

            CARD LOOKUP
            You have one tool, lookupCard(cardName), that returns a card's exact Oracle text and its official rulings.
            Use it only when you are genuinely unsure how a card works or interacts, and call it at most once or
            twice per decision - every call costs time. Card texts for the cards on the table are already in the
            prompt. After at most a couple of lookups, give your answer in the required format.
            """;

    static final String SUMMARY_INSTRUCTIONS = """
            You are the memory of a Magic: The Gathering player. You are given notes about what the player did, with
            the reasons. Compress them into at most 120 words of plain text that the player can read at the start
            of a later turn: the plan being followed, what the opponent has and seems to be doing, cards being
            held back and why, and anything to remember. Reply with the summary only - no actions, no preamble.
            """;

    /** One open conversation of a session, as the history items a stateless Responses request needs. */
    private static final class Conversation {
        final List<JsonObject> items = new ArrayList<>();
        volatile int usedTokens;
    }

    /** The finished answer to one decision. */
    private record Reply(String text, String reasoning, String finish) { }

    /** One function call the model asked for. */
    private record ToolCall(String id, String callId, String name, JsonObject arguments) { }

    /** Everything read out of one Responses API answer. */
    private record Parsed(String text, String reasoning, String messageId, int inputTokens, int outputTokens,
                          int totalTokens, String status, List<ToolCall> calls) { }

    private final RawInferenceSettings settings;
    private final String endpoint;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
    private final Function<String, String> cardLookup; // null = no tool offered
    private final String systemPrompt;
    private final int contextTokens;

    /** Validates the settings and connects; nothing is sent until the first decision. */
    public static RawOpenAiAgent connect(RawInferenceSettings settings) {
        if (settings.endpoint == null || settings.endpoint.isBlank()) {
            throw new RawInferenceException(RawInferenceSettings.ENDPOINT_ENV + " is not set: give the API base URL, "
                    + "e.g. https://openrouter.ai/api/v1 (a request goes to <endpoint>/responses)");
        }
        if (settings.apiKey == null || settings.apiKey.isBlank()) {
            throw new RawInferenceException(RawInferenceSettings.API_KEY_ENV + " is not set: give the API key of the "
                    + "provider (for OpenRouter, the key configured in your opencode installation)");
        }
        if (settings.model == null || settings.model.isBlank()) {
            throw new RawInferenceException("no model configured; set --oc-model, e.g. --oc-model inclusionai/ling-3.1-flash");
        }
        return new RawOpenAiAgent(settings, null);
    }

    /** @param cardLookup for tests: a replacement for the MCP-backed lookup, or null to use the settings' card server. */
    RawOpenAiAgent(RawInferenceSettings settings, Function<String, String> cardLookup) {
        this.settings = settings;
        this.endpoint = settings.endpoint.endsWith("/")
                ? settings.endpoint.substring(0, settings.endpoint.length() - 1)
                : settings.endpoint;
        if (cardLookup != null) {
            this.cardLookup = cardLookup;
        } else if (settings.cardServerUrl != null && !settings.cardServerUrl.isBlank()) {
            final McpCardLookup mcp = new McpCardLookup(settings.cardServerUrl);
            this.cardLookup = mcp::lookup;
        } else {
            this.cardLookup = null;
        }
        this.contextTokens = settings.contextTokens > 0 ? settings.contextTokens : DEFAULT_CONTEXT;
        this.systemPrompt = SystemPrompt.TEXT + (this.cardLookup == null ? "" : LOOKUP_ADDENDUM);
        Logger.info("raw inference agent: model {} at {} with a {}-token context window", settings.model, endpoint, contextTokens);
    }

    @Override
    public int contextTokens() {
        return contextTokens;
    }

    @Override
    public String systemPrompt() {
        return systemPrompt;
    }

    // ---- decisions -------------------------------------------------------------------------------------

    @Override
    public AgentChoice decide(AgentRequest request) {
        Conversation conversation = conversations.get(request.sessionKey());
        if (request.newSession()) {
            if (conversation != null) {
                conversations.remove(request.sessionKey());
            }
            conversation = null;
        } else if (conversation == null) {
            // e.g. the previous request of this session failed before the conversation was created
            throw new ContextOverflowException("no open conversation for " + request.sessionKey() + "; the full context must be resent");
        }

        final int promptTokens = estimateTokens(request.prompt());
        final int fixed = REQUEST_OVERHEAD_TOKENS + estimateTokens(systemPrompt) + (cardLookup == null ? 0 : LOOKUP_TOOL_TOKENS);
        final int used = conversation == null ? 0 : conversation.usedTokens;
        final int available = contextTokens - settings.outputReserveTokens;
        if (used + fixed + promptTokens > available) {
            if (conversation != null) {
                conversations.remove(request.sessionKey());
            }
            throw new ContextOverflowException("about " + (used + fixed + promptTokens) + " tokens needed, " + available + " available");
        }

        if (conversation == null) {
            conversation = new Conversation();
            conversations.put(request.sessionKey(), conversation);
        }
        appendUser(conversation, request.prompt());

        Reply reply = ask(conversation, request.sessionKey());
        AgentChoice choice = tryParse(reply);
        if (choice == null) {
            Logger.info("raw inference agent: unreadable reply, sending a format reminder. Reply was: '{}' (reasoning {} chars, finish {})",
                    abbreviate(reply.text()), reply.reasoning().length(), reply.finish());
            appendUser(conversation, FORMAT_REMINDER);
            reply = ask(conversation, request.sessionKey());
            choice = tryParse(reply);
        }
        return choice; // null = still unreadable: the controller asks again or falls back
    }

    /**
     * Sends the conversation and follows up tool calls (executed against the card server) until the model
     * answers with text; appends everything to the conversation so the whole exchange is part of history.
     */
    private Reply ask(Conversation conversation, String sessionKey) {
        for (int step = 0; step < settings.maxSteps; step++) {
            final Parsed parsed = parseResponse(send(conversation));
            conversation.usedTokens = parsed.totalTokens() > 0 ? parsed.totalTokens()
                    : conversation.usedTokens + estimateTokens(lastUserText(conversation)) + estimateTokens(parsed.text());
            if (!parsed.calls().isEmpty()) {
                if (!parsed.text().isBlank()) {
                    appendAssistant(conversation, parsed);
                }
                for (ToolCall call : parsed.calls()) {
                    appendFunctionCall(conversation, call);
                    final String output = TOOL_LOOKUP_CARD.equals(call.name()) && cardLookup != null
                            ? cardLookup.apply(argument(call, "cardName"))
                            : "Error: unknown tool '" + call.name() + "'.";
                    Logger.info("raw inference agent: used the {} tool ({} characters)", call.name(), output.length());
                    appendFunctionCallOutput(conversation, call, output);
                }
                continue;
            }
            appendAssistant(conversation, parsed);
            return new Reply(parsed.text(), parsed.reasoning(), parsed.status());
        }
        throw new RawInferenceException("the model kept calling tools without answering (" + settings.maxSteps + " steps); "
                + "check the card server at " + settings.cardServerUrl);
    }

    // ---- memory compression ----------------------------------------------------------------------------

    @Override
    public String summarize(SummaryRequest request) {
        final StringBuilder prompt = new StringBuilder();
        if (request.priorSummary() != null && !request.priorSummary().isBlank()) {
            prompt.append("Earlier summary (keep what is still relevant):\n").append(request.priorSummary()).append("\n\n");
        }
        prompt.append(request.kind() == SummaryRequest.Kind.TURN ? "Notes about the turn just finished:\n"
                : "Notes about what you did at instant speed during the opponents' turns:\n");
        final int fixedTokens = REQUEST_OVERHEAD_TOKENS + estimateTokens(SUMMARY_INSTRUCTIONS);
        final int budgetChars = Math.max(200, (int) ((contextTokens - settings.outputReserveTokens - fixedTokens) * CHARS_PER_TOKEN) - prompt.length());
        final String notes = request.transcript() == null ? "" : request.transcript();
        prompt.append(notes.length() <= budgetChars ? notes : "..." + notes.substring(notes.length() - budgetChars));

        final String text = sendOnce(SUMMARY_INSTRUCTIONS, prompt.toString());
        if (text.isBlank()) {
            throw new RawInferenceException("the model returned an empty summary");
        }
        return text;
    }

    // ---- lifecycle -------------------------------------------------------------------------------------

    @Override
    public void endSession(String sessionKey) {
        conversations.remove(sessionKey);
    }

    @Override
    public void close() {
        conversations.clear();
    }

    // ---- HTTP ------------------------------------------------------------------------------------------

    /** One stateless request with the whole history; the instructions go in "instructions" (tested against OpenRouter). */
    private JsonObject send(Conversation conversation) {
        final JsonObject body = baseRequest(systemPrompt, settings.outputReserveTokens);
        final JsonArray input = new JsonArray();
        for (JsonObject item : conversation.items) {
            input.add(item);
        }
        body.add("input", input);
        addTools(body);
        return post(body);
    }

    /** A one-shot request that is not part of any conversation (summaries). */
    private String sendOnce(String instructions, String userText) {
        final JsonObject body = baseRequest(instructions, settings.outputReserveTokens);
        final JsonObject message = new JsonObject();
        message.addProperty("type", "message");
        message.addProperty("role", "user");
        final JsonArray content = new JsonArray();
        final JsonObject part = new JsonObject();
        part.addProperty("type", "input_text");
        part.addProperty("text", userText);
        content.add(part);
        message.add("content", content);
        final JsonArray input = new JsonArray();
        input.add(message);
        body.add("input", input);
        return parseResponse(post(body)).text();
    }

    private JsonObject baseRequest(String instructions, int maxOutputTokens) {
        final JsonObject body = new JsonObject();
        body.addProperty("model", settings.model);
        body.addProperty("instructions", instructions);
        body.addProperty("max_output_tokens", maxOutputTokens);
        body.addProperty("temperature", settings.temperature);
        return body;
    }

    private void addTools(JsonObject body) {
        if (cardLookup == null) {
            return;
        }
        final JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.addProperty("name", TOOL_LOOKUP_CARD);
        tool.addProperty("description", "Looks up ONE Magic: The Gathering card by its exact name and returns its mana "
                + "cost, type, current Oracle text and official rulings. Use it during a game when you are unsure how a "
                + "card works or interacts. Returns plain text.");
        final JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        final JsonObject properties = new JsonObject();
        final JsonObject cardName = new JsonObject();
        cardName.addProperty("type", "string");
        cardName.addProperty("description", "Exact card name, e.g. 'Lightning Bolt'. For a double-faced or split card either face name works.");
        properties.add("cardName", cardName);
        parameters.add("properties", properties);
        final JsonArray required = new JsonArray();
        required.add("cardName");
        parameters.add("required", required);
        tool.add("parameters", parameters);
        final JsonArray tools = new JsonArray();
        tools.add(tool);
        body.add("tools", tools);
        body.addProperty("tool_choice", "auto");
    }

    private JsonObject post(JsonObject body) {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/responses"))
                .header("Authorization", "Bearer " + settings.apiKey)
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        final HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RawInferenceException("the model call was interrupted", e);
        } catch (IOException e) {
            throw new RawInferenceException("cannot reach " + endpoint + ": " + e.getMessage(), e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            final String message = errorMessage(response.body());
            if (looksLikeOverflow(message)) {
                throw new ContextOverflowException("raw provider: " + message);
            }
            throw new RawInferenceException("the model reported an error (HTTP " + response.statusCode() + "): " + message);
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static String errorMessage(String body) {
        try {
            final JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("error") && json.get("error").isJsonObject()) {
                final JsonObject error = json.getAsJsonObject("error");
                String message = error.has("message") && !error.get("message").isJsonNull() ? error.get("message").getAsString() : body;
                if (error.has("metadata") && error.get("metadata").isJsonObject()) {
                    final JsonObject metadata = error.getAsJsonObject("metadata");
                    if (metadata.has("raw") && metadata.get("raw").isJsonPrimitive()) {
                        return message + " (" + metadata.get("raw").getAsString() + ")";
                    }
                }
                return message;
            }
            return body;
        } catch (RuntimeException e) {
            return body;
        }
    }

    // ---- history items ---------------------------------------------------------------------------------

    private static void appendUser(Conversation conversation, String text) {
        final JsonObject message = new JsonObject();
        message.addProperty("type", "message");
        message.addProperty("role", "user");
        final JsonArray content = new JsonArray();
        final JsonObject part = new JsonObject();
        part.addProperty("type", "input_text");
        part.addProperty("text", text);
        content.add(part);
        message.add("content", content);
        conversation.items.add(message);
    }

    private static void appendAssistant(Conversation conversation, Parsed parsed) {
        if (parsed.text().isBlank()) {
            return;
        }
        final JsonObject message = new JsonObject();
        message.addProperty("type", "message");
        message.addProperty("role", "assistant");
        if (parsed.messageId() != null) {
            message.addProperty("id", parsed.messageId());
        }
        message.addProperty("status", "completed");
        final JsonArray content = new JsonArray();
        final JsonObject part = new JsonObject();
        part.addProperty("type", "output_text");
        part.addProperty("text", parsed.text());
        part.add("annotations", new JsonArray());
        content.add(part);
        message.add("content", content);
        conversation.items.add(message);
    }

    private static void appendFunctionCall(Conversation conversation, ToolCall call) {
        final JsonObject item = new JsonObject();
        item.addProperty("type", "function_call");
        if (call.id() != null) {
            item.addProperty("id", call.id());
        }
        item.addProperty("call_id", call.callId());
        item.addProperty("name", call.name());
        item.addProperty("arguments", call.arguments() == null ? "{}" : call.arguments().toString());
        conversation.items.add(item);
    }

    private static void appendFunctionCallOutput(Conversation conversation, ToolCall call, String output) {
        final JsonObject item = new JsonObject();
        item.addProperty("type", "function_call_output");
        item.addProperty("call_id", call.callId());
        item.addProperty("output", output);
        conversation.items.add(item);
    }

    private static String argument(ToolCall call, String name) {
        final JsonObject args = call.arguments();
        if (args == null || !args.has(name) || args.get(name).isJsonNull()) {
            return "";
        }
        return args.get(name).getAsString();
    }

    private static String lastUserText(Conversation conversation) {
        for (int i = conversation.items.size() - 1; i >= 0; i--) {
            final JsonObject item = conversation.items.get(i);
            if (item.has("role") && "user".equals(item.get("role").getAsString())) {
                try {
                    return item.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
                } catch (RuntimeException e) {
                    return "";
                }
            }
        }
        return "";
    }

    // ---- parsing ---------------------------------------------------------------------------------------

    private static AgentChoice tryParse(Reply reply) {
        if (reply.text().isBlank()) {
            return null;
        }
        try {
            return ChoiceParser.parse(reply.text());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Reads one Responses API answer: text, reasoning, token counts, status, and any function calls. */
    static Parsed parseResponse(JsonObject response) {
        final StringBuilder text = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        String messageId = null;
        final List<ToolCall> calls = new ArrayList<>();
        if (response.has("output") && response.get("output").isJsonArray()) {
            for (JsonElement element : response.getAsJsonArray("output")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                final JsonObject item = element.getAsJsonObject();
                final String type = item.has("type") && item.get("type").isJsonPrimitive() ? item.get("type").getAsString() : "";
                switch (type) {
                    case "message" -> {
                        if (messageId == null && item.has("id") && !item.get("id").isJsonNull()) {
                            messageId = item.get("id").getAsString();
                        }
                        if (item.has("content") && item.get("content").isJsonArray()) {
                            for (JsonElement part : item.getAsJsonArray("content")) {
                                final JsonObject p = part.getAsJsonObject();
                                if (p.has("type") && "output_text".equals(p.get("type").getAsString()) && p.has("text")) {
                                    if (text.length() > 0) {
                                        text.append('\n');
                                    }
                                    text.append(p.get("text").getAsString());
                                }
                            }
                        }
                    }
                    case "function_call" -> calls.add(new ToolCall(
                            item.has("id") && !item.get("id").isJsonNull() ? item.get("id").getAsString() : null,
                            item.has("call_id") && !item.get("call_id").isJsonNull() ? item.get("call_id").getAsString()
                                    : item.has("id") && !item.get("id").isJsonNull() ? item.get("id").getAsString() : "call_" + calls.size(),
                            item.has("name") && !item.get("name").isJsonNull() ? item.get("name").getAsString() : "",
                            item.has("arguments") && item.get("arguments").isJsonPrimitive()
                                    ? parseArguments(item.get("arguments").getAsString()) : new JsonObject()));
                    case "reasoning" -> {
                        if (item.has("summary") && item.get("summary").isJsonArray()) {
                            for (JsonElement part : item.getAsJsonArray("summary")) {
                                final JsonObject p = part.getAsJsonObject();
                                if (p.has("text") && p.get("text").isJsonPrimitive()) {
                                    if (reasoning.length() > 0) {
                                        reasoning.append(' ');
                                    }
                                    reasoning.append(p.get("text").getAsString());
                                }
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        int input = 0;
        int output = 0;
        int total = 0;
        if (response.has("usage") && response.get("usage").isJsonObject()) {
            final JsonObject usage = response.getAsJsonObject("usage");
            input = intOr(usage, "input_tokens");
            output = intOr(usage, "output_tokens");
            total = intOr(usage, "total_tokens");
            if (total == 0) {
                total = input + output;
            }
        }
        final String status = response.has("status") && response.get("status").isJsonPrimitive()
                ? response.get("status").getAsString() : "completed";
        String finish = status;
        if (!"completed".equals(status) && response.has("incomplete_details") && response.get("incomplete_details").isJsonObject()) {
            final JsonObject details = response.getAsJsonObject("incomplete_details");
            if (details.has("reason") && details.get("reason").isJsonPrimitive()) {
                finish = status + ": " + details.get("reason").getAsString();
            }
        }
        return new Parsed(text.toString().trim(), reasoning.toString(), messageId, input, output, total, finish, List.copyOf(calls));
    }

    private static JsonObject parseArguments(String text) {
        try {
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static int intOr(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsInt() : 0;
    }

    static int estimateTokens(String text) {
        return text == null ? 0 : (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    private static boolean looksLikeOverflow(String error) {
        final String e = error.toLowerCase(Locale.ROOT);
        return e.contains("context length") || e.contains("context_length") || e.contains("context window")
                || e.contains("maximum context") || e.contains("too many tokens") || e.contains("exceeds the available context")
                || e.contains("prompt is too long");
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
