package forge.llm.openai;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import forge.llm.agent.AgentRequest;
import forge.llm.agent.AgentChoice;
import forge.llm.agent.ContextOverflowException;
import forge.llm.agent.DecisionKind;

public class RawOpenAiAgentTest {
    private HttpServer server;
    private final List<JsonObject> requests = new ArrayList<>();
    private final List<Integer> statuses = new ArrayList<>();
    private final List<String> responses = new ArrayList<>();

    @BeforeMethod
    public void startFakeApi() throws IOException {
        requests.clear();
        statuses.clear();
        responses.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(JsonParser.parseString(body).getAsJsonObject());
            final int index = requests.size() - 1;
            final int status = index < statuses.size() ? statuses.get(index) : 200;
            final String response = index < responses.size() ? responses.get(index) : apiReply("ACTION 0");
            final byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterMethod
    public void stopFakeApi() {
        server.stop(0);
    }

    private RawInferenceSettings settings() {
        final RawInferenceSettings s = new RawInferenceSettings();
        s.endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        s.apiKey = "test-key";
        s.model = "test/model";
        return s;
    }

    private static String apiReply(String text) {
        return """
                {"status":"completed","output":[{"type":"message","id":"msg_1","role":"assistant","status":"completed",
                "content":[{"type":"output_text","text":%s}]}],
                "usage":{"input_tokens":100,"output_tokens":10,"total_tokens":110}}""".formatted(JsonParser.parseString(quote(text)));
    }

    private static String apiToolCall(String name, String arguments) {
        return """
                {"status":"completed","output":[{"type":"function_call","id":"fc_1","call_id":"call_1","name":%s,"arguments":%s}],
                "usage":{"input_tokens":100,"output_tokens":10,"total_tokens":110}}""".formatted(
                JsonParser.parseString(quote(name)), JsonParser.parseString(quote(arguments)));
    }

    private static String quote(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }

    private static AgentRequest request(String key, boolean fresh, String prompt) {
        return new AgentRequest(key, fresh, DecisionKind.PRIORITY, prompt, List.of());
    }

    @Test
    public void aPlainReplyBecomesAChoiceAndTheRequestCarriesTheSystemPrompt() {
        responses.add(apiReply("ACTION 2\nREASON: fine"));
        final RawOpenAiAgent agent = new RawOpenAiAgent(settings(), null);

        final AgentChoice choice = agent.decide(request("S", true, "hello"));

        assertEquals(choice.actionIds(), List.of(2));
        assertEquals(choice.reasoning(), "fine");
        final JsonObject sent = requests.get(0);
        assertEquals(sent.get("model").getAsString(), "test/model");
        assertTrue(sent.get("instructions").getAsString().contains("expert Magic"), "the game instructions are sent");
        assertEquals(sent.getAsJsonArray("input").get(0).getAsJsonObject().get("role").getAsString(), "user");
        assertEquals(sent.get("max_output_tokens").getAsInt(), 3072);
    }

    @Test
    public void theWholeHistoryIsResentBecauseTheApiIsStateless() {
        responses.add(apiReply("ACTION 0"));
        responses.add(apiReply("ACTION 1"));
        final RawOpenAiAgent agent = new RawOpenAiAgent(settings(), null);

        agent.decide(request("S", true, "first"));
        agent.decide(request("S", false, "second"));

        assertEquals(requests.size(), 2);
        final var secondInput = requests.get(1).getAsJsonArray("input");
        assertEquals(secondInput.size(), 3, "user, assistant, user");
        assertEquals(secondInput.get(0).getAsJsonObject().get("role").getAsString(), "user");
        assertEquals(secondInput.get(1).getAsJsonObject().get("role").getAsString(), "assistant");
        assertEquals(secondInput.get(1).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString(), "ACTION 0");
        assertEquals(secondInput.get(2).getAsJsonObject().get("role").getAsString(), "user");
    }

    @Test
    public void aNewSessionStartsFromAnEmptyHistory() {
        responses.add(apiReply("ACTION 0"));
        responses.add(apiReply("ACTION 1"));
        final RawOpenAiAgent agent = new RawOpenAiAgent(settings(), null);

        agent.decide(request("S", true, "first"));
        agent.decide(request("S", true, "restart"));

        assertEquals(requests.get(1).getAsJsonArray("input").size(), 1);
    }

    @Test
    public void aToolCallIsExecutedAndTheResultGoesBackIntoTheConversation() {
        final List<String> lookups = new ArrayList<>();
        responses.add(apiToolCall("lookupCard", "{\"cardName\":\"Grizzly Bears\"}"));
        responses.add(apiReply("ACTION 1\nREASON: looked it up"));
        final RawOpenAiAgent agent = new RawOpenAiAgent(settings(), name -> {
            lookups.add(name);
            return "Grizzly Bears {1}{G} Creature - Bear 2/2";
        });

        final AgentChoice choice = agent.decide(request("S", true, "what does grizzly bears do?"));

        assertEquals(choice.actionIds(), List.of(1));
        assertEquals(lookups, List.of("Grizzly Bears"));
        assertEquals(requests.get(0).getAsJsonArray("tools").get(0).getAsJsonObject().get("name").getAsString(), "lookupCard");
        final var followUp = requests.get(1).getAsJsonArray("input");
        assertEquals(followUp.get(followUp.size() - 2).getAsJsonObject().get("type").getAsString(), "function_call");
        final JsonObject toolOutput = followUp.get(followUp.size() - 1).getAsJsonObject();
        assertEquals(toolOutput.get("type").getAsString(), "function_call_output");
        assertEquals(toolOutput.get("call_id").getAsString(), "call_1");
        assertTrue(toolOutput.get("output").getAsString().contains("Grizzly Bears"));
    }

    @Test
    public void anUnreadableReplyGetsOneFormatReminder() {
        responses.add(apiReply("I am thinking about the situation..."));
        responses.add(apiReply("PASS"));
        final RawOpenAiAgent agent = new RawOpenAiAgent(settings(), null);

        final AgentChoice choice = agent.decide(request("S", true, "hello"));

        assertEquals(choice.actionIds(), List.of(0));
        final var secondInput = requests.get(1).getAsJsonArray("input");
        final String reminded = secondInput.get(secondInput.size() - 1).getAsJsonObject()
                .getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(reminded.contains("could not be understood"));
    }

    @Test
    public void anOverflowErrorAsksForACompactRestartAndOtherErrorsAreFailures() {
        statuses.add(400);
        responses.add("{\"error\":{\"message\":\"This model's maximum context length is 8192 tokens\"}}");
        final RawOpenAiAgent agent = new RawOpenAiAgent(settings(), null);
        expectThrows(ContextOverflowException.class, () -> agent.decide(request("S", true, "one too many")));

        statuses.add(500);
        responses.add("{\"error\":{\"message\":\"upstream exploded\"}}");
        final RawOpenAiAgent other = new RawOpenAiAgent(settings(), null);
        expectThrows(RawInferenceException.class, () -> other.decide(request("T", true, "hello")));
    }

    @Test
    public void aConversationWithoutAnOpenSessionMustResendTheFullContext() {
        final RawOpenAiAgent agent = new RawOpenAiAgent(settings(), null);
        expectThrows(ContextOverflowException.class, () -> agent.decide(request("ghost", false, "delta")));
    }

    @Test
    public void connectingRequiresEndpointKeyAndModel() {
        final RawInferenceSettings noEndpoint = settings();
        noEndpoint.endpoint = null;
        expectThrows(RawInferenceException.class, () -> RawOpenAiAgent.connect(noEndpoint));

        final RawInferenceSettings noKey = settings();
        noKey.apiKey = " ";
        expectThrows(RawInferenceException.class, () -> RawOpenAiAgent.connect(noKey));

        final RawInferenceSettings noModel = settings();
        noModel.model = null;
        expectThrows(RawInferenceException.class, () -> RawOpenAiAgent.connect(noModel));

        assertEquals(RawOpenAiAgent.connect(settings()).contextTokens(), 131_072, "0 picks the conservative default");
        settings().contextTokens = 262_144;
    }

    @Test
    public void configuredContextAndReserveReachTheRequestAndTheBudget() {
        final RawInferenceSettings s = settings();
        s.contextTokens = 50_000;
        s.outputReserveTokens = 1234;
        responses.add(apiReply("ACTION 0"));
        final RawOpenAiAgent agent = new RawOpenAiAgent(s, null);
        agent.decide(request("S", true, "small talk"));

        assertEquals(requests.get(0).get("max_output_tokens").getAsInt(), 1234);
        assertEquals(agent.contextTokens(), 50_000);
    }
}
