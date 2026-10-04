package forge.llm.run;

import java.util.List;
import java.util.Map;

import forge.llm.action.GameAction;
import forge.llm.agent.AgentChoice;
import forge.llm.agent.AgentRequest;
import forge.llm.agent.ContextOverflowException;
import forge.llm.agent.DecisionKind;
import forge.llm.agent.SummaryRequest;
import forge.llm.opencode.OpencodeAgent;
import forge.llm.opencode.OpencodeSettings;

/**
 * Sanity check for an opencode + model setup, without a game: launches (or attaches to) opencode, puts a canned
 * decision to the model, then a follow-up in the same conversation, then asks for a summary, and prints what came
 * back and how long each step took. Takes the same --oc-* / --mcp-url options as {@link LlmMatchRunner}.
 *
 * <pre>
 * java -cp ... forge.llm.run.OpencodeCheck [--oc-model lmstudio/google/gemma-4-e2b] [--oc-context 16384]
 *          [--mcp-url http://127.0.0.1:3041/] [--oc-log-level DEBUG] ...
 * </pre>
 * It needs no Forge resources, so it can be run from any directory.
 */
public final class OpencodeCheck {
    private OpencodeCheck() {
    }

    private static final String FIRST = """
            # GAME
            You are Alice. Format: Constructed. Opponents: Bob.

            # CURRENT STATE
            Turn 3 - Alice's turn - Main phase, precombat - priority: Alice
            ### YOU: Alice | life 20 | hand 3 | library 50
            Battlefield:
              Lands: Island x2 (2 untapped)
            ### OPPONENT: Bob | life 20 | hand 4 | library 48
            Battlefield:
              Creatures:
                - Goblin Guide (#77) 2/2 [Creature - Goblin] untapped

            ## Your hand
            - Counterspell (#12) {U}{U} Instant
            - Air Elemental (#13) {3}{U}{U} Creature - Elemental
            - Island (#14) Basic Land - Island

            # DECISION
            You have priority. Choose ONE action (id 0 passes).

            ## AVAILABLE ACTIONS (choose from these ids only)
            [0] Pass priority (with an empty stack the game moves on to the next step/phase)
            [1] Play land: Island (#14)

            ## CANNOT DO RIGHT NOW (do not attempt)
            - Air Elemental (#13) - cannot pay the mana cost {3}{U}{U} (2 untapped mana sources)
            - Counterspell (#12) - no legal target
            """;

    private static final String SECOND = """
            # UPDATE (decision 2 of this session)
            ## What happened since your last decision
            Alice played Island (14).

            # DECISION
            You have priority. Choose ONE action (id 0 passes).

            ## AVAILABLE ACTIONS (choose from these ids only)
            [0] Pass priority (with an empty stack the game moves on to the next step/phase)
            """;

    public static void main(String[] args) throws Exception {
        final Map<String, String> opts = LlmMatchRunner.parse(args);
        final OpencodeSettings settings = LlmMatchRunner.opencodeSettings(opts);
        System.out.println("Model: " + settings.model + (settings.cardServerUrl != null ? ", card server " + settings.cardServerUrl : ""));

        long t = System.nanoTime();
        try (OpencodeAgent agent = OpencodeAgent.connect(settings)) {
            System.out.printf("opencode ready in %.1fs, context window %d tokens%n", seconds(t), agent.contextTokens());

            t = System.nanoTime();
            final AgentChoice first = ask(agent, "check-turn", true, FIRST);
            System.out.printf("decision 1 (%.1fs): %s%n", seconds(t), describe(first));

            t = System.nanoTime();
            final AgentChoice second = ask(agent, "check-turn", false, SECOND);
            System.out.printf("decision 2, same conversation (%.1fs): %s%n", seconds(t), describe(second));

            agent.endSession("check-turn");
            t = System.nanoTime();
            final String summary = agent.summarize(new SummaryRequest(SummaryRequest.Kind.TURN, "Alice",
                    "T3 Main: Play land: Island (reason: need a third land for Air Elemental)\nT3 End: passed (reason: holding Counterspell mana)", ""));
            System.out.printf("summary (%.1fs): %s%n", seconds(t), summary);
        }
        System.exit(0);
    }

    private static AgentChoice ask(OpencodeAgent agent, String key, boolean fresh, String prompt) {
        try {
            return agent.decide(new AgentRequest(key, fresh, DecisionKind.PRIORITY, prompt, List.<GameAction>of()));
        } catch (ContextOverflowException e) {
            System.out.println("  context overflow: " + e.getMessage());
            return null;
        }
    }

    private static String describe(AgentChoice c) {
        return c == null ? "(no readable answer)" : "ids " + c.actionIds() + ", reason: " + c.reasoning();
    }

    private static double seconds(long since) {
        return (System.nanoTime() - since) / 1e9;
    }
}
