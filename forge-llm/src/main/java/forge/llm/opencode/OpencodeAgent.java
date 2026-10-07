package forge.llm.opencode;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.tinylog.Logger;

import forge.llm.agent.AgentChoice;
import forge.llm.agent.AgentRequest;
import forge.llm.agent.ChoiceParser;
import forge.llm.agent.ContextOverflowException;
import forge.llm.agent.ModelAgent;
import forge.llm.agent.SummaryRequest;

/**
 * A {@link ModelAgent} backed by an LLM that is reached through opencode's HTTP server.
 *
 * <ul>
 *   <li>One opencode session per forge-llm session key: all decisions of a turn happen in one conversation, so
 *       the model keeps full context as the turn progresses.</li>
 *   <li>The forge-player agent defined by {@link OpencodeConfigBuilder} supplies the game instructions and, if a
 *       card server is configured, the card-lookup tool; opencode runs the tool loop internally.</li>
 *   <li>The model's reply is parsed with {@link ChoiceParser}; an unreadable reply is answered once with a short
 *       reminder of the format before it is given up on.</li>
 *   <li>The conversation size is tracked from the token counts opencode reports. A request that would not fit
 *       the model's context window is not sent; {@link ContextOverflowException} tells the controller to start
 *       over with a compact prompt.</li>
 * </ul>
 */
public final class OpencodeAgent implements ModelAgent {
    /** Conservative characters-per-token for English prose plus card text and digits. */
    private static final double CHARS_PER_TOKEN = 3.2;
    /** Tokens opencode adds to every request on top of the conversation: instructions, tool definitions, environment. */
    private static final int REQUEST_OVERHEAD_TOKENS = 1_500;
    private static final int LOOKUP_TOOL_TOKENS = 700;
    private static final int DEFAULT_CONTEXT = 16_384;

    static final String FORMAT_REMINDER = """
            Your reply could not be understood. Reply with exactly one line of the form
              ACTION <id>        (or ACTIONS <id>, <id> for several, or PASS, or NONE)
            followed, if you like, by a line  REASON: <short reason>.
            Use only ids from the AVAILABLE ACTIONS list above.""";

    private final OpencodeClient client;
    private final OpencodeSettings settings;
    private final OpencodeServer server; // null when attached to an existing server
    private final int contextTokens;
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    /** One opencode session and how full it is. */
    private static final class Conversation {
        final String sessionId;
        volatile int usedTokens;

        Conversation(String sessionId) {
            this.sessionId = sessionId;
        }
    }

    /** Launches (or attaches to) an opencode server as the settings say. */
    public static OpencodeAgent connect(OpencodeSettings settings) {
        if (settings.serverUrl != null) {
            final String password = settings.serverPassword != null ? settings.serverPassword : System.getenv("OPENCODE_SERVER_PASSWORD");
            final OpencodeClient client = new OpencodeClient(settings.serverUrl, settings.serverUsername, password);
            if (!client.isHealthy()) {
                throw new OpencodeException("no healthy opencode server at " + settings.serverUrl);
            }
            if (!client.hasAgent(OpencodeSettings.AGENT_NAME) || !client.hasAgent(OpencodeSettings.SUMMARIZER_NAME)) {
                throw new OpencodeException("the opencode server at " + settings.serverUrl + " has no '" + OpencodeSettings.AGENT_NAME
                        + "' / '" + OpencodeSettings.SUMMARIZER_NAME + "' agents. Nothing is injected into a server that is already running "
                        + "(see OpencodeConfigBuilder for their definitions); let forge-llm launch the server itself instead");
            }
            return new OpencodeAgent(client, settings, null);
        }
        final OpencodeServer server = OpencodeServer.start(settings);
        return new OpencodeAgent(new OpencodeClient(server.baseUrl(), server.username(), server.password()), settings, server);
    }

    /** For tests: use a given client (e.g. one talking to a fake server). */
    public OpencodeAgent(OpencodeClient client, OpencodeSettings settings, OpencodeServer server) {
        this.client = client;
        this.settings = settings;
        this.server = server;
        int context = settings.contextTokens;
        if (context <= 0) {
            context = client.contextLimit(settings.providerId(), settings.modelId());
        }
        this.contextTokens = context > 0 ? context : DEFAULT_CONTEXT;
        Logger.info("opencode agent: model {} with a {}-token context window", settings.model, contextTokens);
    }

    /** The standing instructions the model works under (the forge-player agent's prompt), for transcripts. */
    public String systemPrompt() {
        return OpencodeConfigBuilder.agentPrompt(settings);
    }

    public int contextTokens() {
        return contextTokens;
    }

    // ---- decisions -------------------------------------------------------------------------------------

    @Override
    public AgentChoice decide(AgentRequest request) {
        Conversation conversation = conversations.get(request.sessionKey());
        if (request.newSession()) {
            if (conversation != null) {
                dropConversation(request.sessionKey());
            }
            conversation = null;
        } else if (conversation == null) {
            // e.g. the previous request of this session failed before the conversation was created
            throw new ContextOverflowException("no open conversation for " + request.sessionKey() + "; the full context must be resent");
        }

        final int promptTokens = estimateTokens(request.prompt());
        final int fixed = conversation == null ? overheadTokens() : 0;
        final int used = conversation == null ? 0 : conversation.usedTokens;
        final int available = contextTokens - settings.outputReserveTokens;
        if (used + fixed + promptTokens > available) {
            final String why = "about " + (used + fixed + promptTokens) + " tokens needed, " + available + " available";
            if (conversation != null) {
                dropConversation(request.sessionKey());
            }
            throw new ContextOverflowException(why);
        }

        if (conversation == null) {
            conversation = new Conversation(client.createSession(request.sessionKey()));
            conversations.put(request.sessionKey(), conversation);
        }

        OpencodeClient.Reply reply = ask(conversation, request.prompt(), request.sessionKey());
        AgentChoice choice = tryParse(reply);
        if (choice == null) {
            Logger.info("opencode agent: unreadable reply, sending a format reminder. Reply was: '{}' (reasoning {} chars, finish {})",
                    abbreviate(reply.text()), reply.reasoning().length(), reply.finish());
            reply = ask(conversation, FORMAT_REMINDER, request.sessionKey());
            choice = tryParse(reply);
        }
        return choice; // null = still unreadable: the controller asks again or falls back
    }

    private OpencodeClient.Reply ask(Conversation conversation, String text, String sessionKey) {
        final OpencodeClient.Reply reply = client.sendMessage(conversation.sessionId, text,
                settings.providerId(), settings.modelId(), OpencodeSettings.AGENT_NAME, settings.variant);
        if (reply.error() != null) {
            if (reply.contextOverflow() || looksLikeOverflow(reply.error())) {
                dropConversation(sessionKey);
                throw new ContextOverflowException(reply.error());
            }
            throw new OpencodeException("the model reported an error: " + reply.error());
        }
        Logger.debug("opencode reply for {} ({} tokens in conversation): {}", sessionKey, reply.totalTokens(), reply.text());
        // The reported total is the size of the conversation as the provider counted it (cached prefix included);
        // estimates are only for providers that report nothing.
        conversation.usedTokens = reply.totalTokens() > 0 ? reply.totalTokens()
                : conversation.usedTokens + estimateTokens(text) + estimateTokens(reply.text());
        if (!reply.toolCalls().isEmpty()) {
            Logger.debug("opencode agent: model used tools {}", reply.toolCalls());
        }
        return reply;
    }

    private static AgentChoice tryParse(OpencodeClient.Reply reply) {
        if (reply.text().isBlank()) {
            return null;
        }
        try {
            return ChoiceParser.parse(reply.text());
        } catch (IllegalArgumentException e) {
            return null;
        }
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
        // leave room for the instructions, the model's answer and the tokens opencode adds
        final int fixedTokens = REQUEST_OVERHEAD_TOKENS + estimateTokens(OpencodeConfigBuilder.SUMMARY_INSTRUCTIONS);
        final int budgetChars = Math.max(200, (int) ((contextTokens - settings.outputReserveTokens - fixedTokens) * CHARS_PER_TOKEN) - prompt.length());
        final String notes = request.transcript() == null ? "" : request.transcript();
        prompt.append(notes.length() <= budgetChars ? notes : "..." + notes.substring(notes.length() - budgetChars));

        final String sessionId = client.createSession("summary-" + request.playerName());
        try {
            final OpencodeClient.Reply reply = client.sendMessage(sessionId, prompt.toString(),
                    settings.providerId(), settings.modelId(), OpencodeSettings.SUMMARIZER_NAME, settings.variant);
            if (reply.error() != null) {
                throw new OpencodeException("summarizer error: " + reply.error());
            }
            Logger.debug("opencode summary reply: text='{}' reasoning='{}' finish={} tools={}", reply.text(), reply.reasoning(), reply.finish(), reply.toolCalls());
            if (reply.text().isBlank()) {
                throw new OpencodeException("the model returned an empty summary");
            }
            return reply.text();
        } finally {
            if (!settings.keepSessions) {
                client.deleteSession(sessionId);
            }
        }
    }

    // ---- lifecycle -------------------------------------------------------------------------------------

    @Override
    public void endSession(String sessionKey) {
        dropConversation(sessionKey);
    }

    private void dropConversation(String sessionKey) {
        final Conversation conversation = conversations.remove(sessionKey);
        if (conversation != null && !settings.keepSessions) {
            client.deleteSession(conversation.sessionId);
        }
    }

    @Override
    public void close() {
        conversations.keySet().forEach(this::dropConversation);
        if (server != null) {
            server.close();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private int overheadTokens() {
        return REQUEST_OVERHEAD_TOKENS + estimateTokens(OpencodeConfigBuilder.agentPrompt(settings))
                + (settings.cardServerUrl != null ? LOOKUP_TOOL_TOKENS : 0);
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
