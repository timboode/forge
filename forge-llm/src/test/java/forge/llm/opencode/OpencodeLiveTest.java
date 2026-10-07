package forge.llm.opencode;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.llm.action.GameAction;
import forge.llm.agent.AgentChoice;
import forge.llm.agent.AgentRequest;
import forge.llm.agent.DecisionKind;
import forge.llm.agent.SummaryRequest;

/**
 * Talks to a REAL opencode and a REAL model - skipped unless asked for, because it needs software and a model
 * the build does not provide and takes minutes with a local model.
 *
 * <pre>
 *   FORGE_LLM_LIVE=true mvn -Pllm -pl forge-llm -am test -Dtest=OpencodeLiveTest
 *       [FORGE_LLM_LIVE_MODEL=lmstudio/google/gemma-4-e2b] [FORGE_LLM_LIVE_CONTEXT=16384]
 *       [FORGE_LLM_LIVE_MCP=http://127.0.0.1:3041/]     (or the same as -Dforge.llm.live... system properties)
 * </pre>
 * With the defaults it needs opencode on the PATH and an OpenRouter key in the environment (OPENROUTER_API_KEY);
 * FORGE_LLM_LIVE_MODEL can point it at a local LM Studio model instead.
 */
public class OpencodeLiveTest {
    private OpencodeAgent agent;

    @BeforeClass
    public void connect() {
        if (!"true".equals(setting("forge.llm.live", "FORGE_LLM_LIVE"))) {
            throw new SkipException("live test: set FORGE_LLM_LIVE=true (or -Dforge.llm.live=true) to run it against a real opencode and model");
        }
        final OpencodeSettings settings = new OpencodeSettings();
        final String model = setting("forge.llm.live.model", "FORGE_LLM_LIVE_MODEL");
        if (model != null) {
            settings.model = model;
        }
        final String context = setting("forge.llm.live.context", "FORGE_LLM_LIVE_CONTEXT");
        settings.contextTokens = context == null ? 16384 : Integer.parseInt(context);
        settings.cardServerUrl = setting("forge.llm.live.mcp", "FORGE_LLM_LIVE_MCP");
        if (settings.providerId().equals("lmstudio") && !reachable(settings.lmStudioBaseUrl + "/models")) {
            throw new SkipException("LM Studio is not answering at " + settings.lmStudioBaseUrl);
        }
        agent = OpencodeAgent.connect(settings);
    }

    @AfterClass(alwaysRun = true)
    public void disconnect() {
        if (agent != null) {
            agent.close();
        }
    }

    @Test
    public void aRealModelAnswersADecisionInTheRequiredFormatAndKeepsTheConversation() {
        final String prompt = """
                # CURRENT STATE
                Turn 1 - Alice's turn - Main phase, precombat - priority: Alice
                ### YOU: Alice | life 20 | hand 2
                Battlefield: (nothing)
                ## Your hand
                - Island (#1) Basic Land - Island
                - Counterspell (#2) {U}{U} Instant
                # DECISION
                You have priority. Choose ONE action (id 0 passes).
                ## AVAILABLE ACTIONS (choose from these ids only)
                [0] Pass priority
                [1] Play land: Island (#1)
                """;
        final AgentChoice first = agent.decide(new AgentRequest("live-1", true, DecisionKind.PRIORITY, prompt, List.<GameAction>of()));
        assertNotNull(first, "the model must answer in the ACTION format (the agent already retried once)");
        assertTrue(first.actionIds().size() == 1 && (first.actionIds().get(0) == 0 || first.actionIds().get(0) == 1), first.toString());

        final AgentChoice second = agent.decide(new AgentRequest("live-1", false, DecisionKind.PRIORITY,
                "# UPDATE\nAlice played Island (1).\n# DECISION\nChoose ONE action.\n[0] Pass priority\n", List.<GameAction>of()));
        assertNotNull(second);
        agent.endSession("live-1");
    }

    @Test
    public void aRealModelSummarizes() {
        final String summary = agent.summarize(new SummaryRequest(SummaryRequest.Kind.TURN, "Alice",
                "T1 Main: Play land: Island (reason: curve)\nT1 End: passed (reason: holding up Counterspell)", ""));
        assertFalse(summary.isBlank());
    }

    /** A setting from a system property or, because Maven does not always hand -D options to the test JVM, an environment variable. */
    private static String setting(String property, String environment) {
        final String value = System.getProperty(property);
        return value != null ? value : System.getenv(environment);
    }

    private static boolean reachable(String url) {
        try {
            final HttpResponse<Void> r = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode() / 100 == 2;
        } catch (Exception e) {
            return false;
        }
    }
}
