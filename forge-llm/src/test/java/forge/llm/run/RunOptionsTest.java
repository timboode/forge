package forge.llm.run;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.util.List;

import org.testng.annotations.Test;

public class RunOptionsTest {

    private static RunOptions parse(String... args) {
        return RunOptions.parse(args);
    }

    @Test
    public void optionsWithAndWithoutValuesAreParsed() {
        RunOptions o = parse("--format", "commander", "--log", "--seed", "42");
        assertEquals(o.get("seed"), "42");
        assertTrue(o.has("log"));
        assertFalse(o.has("games"));
        assertEquals(o.getInt("games", 3), 3);
        assertEquals(o.getInt("seed", 0), 42);
    }

    @Test
    public void aBareWordIsAMistakeNotSilentlyIgnored() {
        assertThrows(IllegalArgumentException.class, () -> parse("commander"));
    }

    @Test
    public void formatDefaultsToConstructedAndRejectsUnknownNames() {
        assertEquals(parse().format(), Format.CONSTRUCTED);
        assertEquals(parse("--format", "Commander").format(), Format.COMMANDER);
        assertThrows(IllegalArgumentException.class, () -> parse("--format", "pauper").format());
    }

    @Test
    public void seatsComeFromTheOptionOrTheCallersDefault() {
        assertEquals(parse().seatKinds("human,llm,ai"), List.of(Seat.Kind.HUMAN, Seat.Kind.LLM, Seat.Kind.AI));
        assertEquals(parse("--seats", "LLM, ai ,ai").seatKinds("human,llm"), List.of(Seat.Kind.LLM, Seat.Kind.AI, Seat.Kind.AI));
        assertThrows(IllegalArgumentException.class, () -> parse("--seats", "llm").seatKinds("llm,ai"));
        assertThrows(IllegalArgumentException.class, () -> parse("--seats", "llm,robot").seatKinds("llm,ai"));
    }

    @Test
    public void deckSpecsAreNumberedFromOneAndOptional() {
        RunOptions o = parse("--deck1", "mine.dck", "--deck3", "random");
        assertEquals(o.deckSpec(1), "mine.dck");
        assertNull(o.deckSpec(2));
        assertEquals(o.deckSpec(3), "random");
    }

    @Test
    public void defaultsApplyOnlyWhenNothingWasGiven() {
        assertEquals(parse().withDefault("agent", "opencode").agentKind(), "opencode");
        assertEquals(parse("--agent", "heuristic").withDefault("agent", "opencode").agentKind(), "heuristic");
    }

    @Test
    public void opencodeSettingsAreReadFromTheOptions() {
        var s = parse("--oc-model", "curator-dev/deepseek-v4", "--oc-context", "500000", "--oc-variant", "high",
                "--oc-output-reserve", "8192",
                "--mcp-url", "http://127.0.0.1:3041/", "--oc-keep-sessions").opencodeSettings();
        assertEquals(s.providerId(), "curator-dev");
        assertEquals(s.modelId(), "deepseek-v4");
        assertEquals(s.contextTokens, 500000);
        assertEquals(s.variant, "high");
        assertEquals(s.outputReserveTokens, 8192);
        assertEquals(s.cardServerUrl, "http://127.0.0.1:3041/");
        assertTrue(s.keepSessions);
    }

    @Test
    public void opencodeDefaultsToDeepSeekFlashOnOpenRouterWithHighEffort() {
        var s = parse().opencodeSettings();
        assertEquals(s.model, "openrouter/~deepseek/deepseek-flash-latest");
        assertEquals(s.providerId(), "openrouter");
        assertEquals(s.modelId(), "~deepseek/deepseek-flash-latest");
        assertEquals(s.variant, "high");
    }

    @Test
    public void theHighEffortDefaultOnlyAppliesToOpenRouterModels() {
        assertEquals(parse("--oc-variant", "low").opencodeSettings().variant, "low");
        assertNull(parse("--oc-model", "lmstudio/google/gemma-4-e2b").opencodeSettings().variant);
    }

    @Test
    public void aLocalModelGetsALongerPerCallTimeoutThanTheDefault() {
        assertEquals(parse("--agent", "opencode").playerConfig().decisionTimeoutSeconds, 900);
        assertEquals(parse("--agent", "opencode", "--decision-timeout", "60").playerConfig().decisionTimeoutSeconds, 60);
        assertEquals(parse().playerConfig().decisionTimeoutSeconds, 180);
    }

    @Test
    public void rulesFilesComeFromTheOptionAndMissingOnesAreReported() throws java.io.IOException {
        final java.nio.file.Path rules = java.nio.file.Files.createTempFile("rules-options", ".txt");
        java.nio.file.Files.writeString(rules, "701.26a summoning sickness.\n");
        try {
            var lib = parse("--rules", rules.toString()).rulesLibrary();
            assertTrue(lib != null, "the given file is loaded");
            assertFalse(lib.isEmpty());
            assertThrows(IllegalArgumentException.class,
                    () -> parse("--rules", "definitely-missing-rules-xyz.txt").rulesLibrary());
        } finally {
            java.nio.file.Files.deleteIfExists(rules);
        }
    }

    @Test
    public void rulesAndRetryOptionsReachThePlayerConfig() {
        var cfg = parse("--rules-lines", "40", "--rules-queries", "2", "--max-retries", "3").playerConfig();
        assertEquals(cfg.maxRulesResultLines, 40);
        assertEquals(cfg.maxRulesQueriesPerDecision, 2);
        assertEquals(cfg.maxRetries, 3);
    }

    @Test
    public void anExplicitCardDatabaseThatDoesNotExistIsReported() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("--card-db", "definitely-missing-cards-xyz.sqlite").rulesLibrary());
    }

    @Test
    public void theInferenceProviderDefaultsToOpencodeAndRejectsUnknownNames() {
        assertEquals(parse().llmProvider(), RunOptions.LLM_PROVIDER_OPENCODE);
        assertEquals(parse("--oc-llm-provider", "raw-inference-openai-compatible").llmProvider(), RunOptions.LLM_PROVIDER_RAW);
        assertThrows(IllegalArgumentException.class, () -> parse("--oc-llm-provider", "lmstudio").llmProvider());
    }

    @Test
    public void rawProviderSettingsComeFromTheCommandLine() {
        var s = parse("--oc-model", "inclusionai/ling-3.1-flash", "--oc-context", "262144",
                "--oc-output-reserve", "8192", "--mcp-url", "http://127.0.0.1:3042/").rawInferenceSettings();
        assertEquals(s.model, "inclusionai/ling-3.1-flash");
        assertEquals(s.contextTokens, 262144);
        assertEquals(s.outputReserveTokens, 8192);
        assertEquals(s.cardServerUrl, "http://127.0.0.1:3042/");
    }
}
