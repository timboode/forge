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
                "--mcp-url", "http://127.0.0.1:3041/", "--oc-keep-sessions").opencodeSettings();
        assertEquals(s.providerId(), "curator-dev");
        assertEquals(s.modelId(), "deepseek-v4");
        assertEquals(s.contextTokens, 500000);
        assertEquals(s.variant, "high");
        assertEquals(s.cardServerUrl, "http://127.0.0.1:3041/");
        assertTrue(s.keepSessions);
    }

    @Test
    public void aLocalModelGetsALongerPerCallTimeoutThanTheDefault() {
        assertEquals(parse("--agent", "opencode").playerConfig().decisionTimeoutSeconds, 900);
        assertEquals(parse("--agent", "opencode", "--decision-timeout", "60").playerConfig().decisionTimeoutSeconds, 60);
        assertEquals(parse().playerConfig().decisionTimeoutSeconds, 180);
    }
}
