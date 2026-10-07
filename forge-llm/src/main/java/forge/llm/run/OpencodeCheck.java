package forge.llm.run;

import java.util.List;

import forge.llm.action.GameAction;
import forge.llm.agent.AgentChoice;
import forge.llm.agent.AgentRequest;
import forge.llm.agent.ContextOverflowException;
import forge.llm.agent.DecisionKind;
import forge.llm.agent.ModelAgent;
import forge.llm.agent.SummaryRequest;
import forge.llm.mcp.McpCardLookup;
import forge.llm.opencode.OpencodeAgent;
import forge.llm.openai.RawOpenAiAgent;
import forge.llm.rules.RulesLibrary;

/**
 * Sanity check for an opencode + model setup, without a game: launches (or attaches to) opencode, puts a canned
 * decision to the model - including a "Query MTG rules" option it may use, which this check serves like the game
 * controller does - then a follow-up in the same conversation, then asks for a summary, and prints what came
 * back and how long each step took. Takes the same --oc-* / --mcp-url / --rules options as {@link LlmMatchRunner}.
 *
 * <pre>
 * java -cp ... forge.llm.run.OpencodeCheck [--oc-model lmstudio/...] [--oc-context 125000]
 *          [--rules "mtg comprehensive rules.txt;mtg commander rules.txt"] [--oc-log-level DEBUG] ...
 * </pre>
 * It needs no Forge resources, so it can be run from any directory.
 */
public final class OpencodeCheck {
    private OpencodeCheck() {
    }

    /** The id of the "Query MTG rules" option in the canned option list below. */
    private static final int QUERY_ID = 2;

    private static final String OPTIONS = """
            ## AVAILABLE ACTIONS (choose from these ids only)
            [0] Pass priority (with an empty stack the game moves on to the next step/phase)
            [1] Play land: Island (#14)
            [2] Query MTG rules - look up a rule or a card first; reply: ACTION 2 'search phrase', 'another phrase'
            """;

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
            You do not know what the keyword 'deathtouch' means. First use the Query MTG rules option to look it
            up: answer a line exactly like
              ACTION 2 'deathtouch'
            After you receive the rules text, choose your action.

            ## AVAILABLE ACTIONS (choose from these ids only)
            [0] Pass priority (with an empty stack the game moves on to the next step/phase)
            [1] Play land: Island (#14)
            [2] Query MTG rules - look up a rule or a card first; reply: ACTION 2 'search phrase', 'another phrase'

            ## CANNOT DO RIGHT NOW (do not attempt)
            - Air Elemental (#13) - cannot pay the mana cost {3}{U}{U} (2 untapped mana sources)
            - Counterspell (#12) - no legal target
            """;

    private static final String CARD = """
            # DECISION
            You need the Oracle text of the card 'Grizzly Bears'. Use the lookupCard tool to fetch it, then choose.

            ## AVAILABLE ACTIONS (choose from these ids only)
            [0] Pass priority (with an empty stack the game moves on to the next step/phase)
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
        final RunOptions options = RunOptions.parse(args);
        final RulesLibrary rules = options.rulesLibrary();
        final boolean raw = RunOptions.LLM_PROVIDER_RAW.equals(options.llmProvider());
        final String model = raw ? options.get("oc-model", "(no --oc-model)") : options.opencodeSettings().model;
        final String cardServer = options.get("mcp-url");
        System.out.println("Provider: " + options.llmProvider() + ", model: " + model
                + (cardServer != null ? ", card server " + cardServer : "")
                + (rules != null ? ", rules: " + rules.describe() : ", no rules files configured"));

        long t = System.nanoTime();
        try (ModelAgent agent = raw ? RawOpenAiAgent.connect(options.rawInferenceSettings())
                : OpencodeAgent.connect(options.opencodeSettings())) {
            System.out.printf("agent ready in %.1fs, context window %d tokens%n", seconds(t), agent.contextTokens());

            if (cardServer != null) {
                t = System.nanoTime();
                final String card = new McpCardLookup(cardServer).lookup("Grizzly Bears");
                System.out.printf("card server direct call (%.1fs): %s%n", seconds(t), oneLine(card));
            }

            t = System.nanoTime();
            AgentChoice choice = ask(agent, "check-turn", true, FIRST);
            System.out.printf("decision 1 (%.1fs): %s%n", seconds(t), describe(choice));

            int served = 0;
            while (choice != null && choice.isRulesQuery(QUERY_ID) && served < 2) {
                served++;
                final String result = rules != null
                        ? rules.search(choice.searchTerms(), RulesLibrary.DEFAULT_MAX_LINES)
                        : "No rules files are configured for this check.";
                System.out.printf("  lookup %d: %s -> %d characters%n", served, choice.searchTerms(), result.length());
                final String followUp = "# RULES LOOKUP RESULT\nYou asked about " + choice.searchTerms() + ":\n\n"
                        + result + "\n\nThe game has not changed. Choose from the AVAILABLE ACTIONS now.\n\n" + OPTIONS;
                t = System.nanoTime();
                choice = ask(agent, "check-turn", false, followUp);
                System.out.printf("  after lookup %d (%.1fs): %s%n", served, seconds(t), describe(choice));
            }
            if (served == 0) {
                System.out.println("  (the model chose an action without a rules lookup)");
            }

            t = System.nanoTime();
            final AgentChoice second = ask(agent, "check-turn", false, SECOND);
            System.out.printf("decision 2, same conversation (%.1fs): %s%n", seconds(t), describe(second));

            agent.endSession("check-turn");
            t = System.nanoTime();
            final String summary = agent.summarize(new SummaryRequest(SummaryRequest.Kind.TURN, "Alice",
                    "T3 Main: Play land: Island (reason: need a third land for Air Elemental)\nT3 End: passed (reason: holding Counterspell mana)", ""));
            System.out.printf("summary (%.1fs): %s%n", seconds(t), summary);

            if (cardServer != null) {
                t = System.nanoTime();
                final AgentChoice card = ask(agent, "check-card", true, CARD);
                System.out.printf("card tool decision (%.1fs): %s%n", seconds(t), describe(card));
            }
        }
        System.exit(0);
    }

    private static AgentChoice ask(ModelAgent agent, String key, boolean fresh, String prompt) {
        try {
            return agent.decide(new AgentRequest(key, fresh, DecisionKind.PRIORITY, prompt, List.<GameAction>of()));
        } catch (ContextOverflowException e) {
            System.out.println("  context overflow: " + e.getMessage());
            return null;
        }
    }

    private static String describe(AgentChoice c) {
        if (c == null) {
            return "(no readable answer)";
        }
        return "ids " + c.actionIds()
                + (c.searchTerms().isEmpty() ? "" : " searchTerms " + c.searchTerms())
                + ", reason: " + c.reasoning();
    }

    private static double seconds(long since) {
        return (System.nanoTime() - since) / 1e9;
    }

    private static String oneLine(String text) {
        final String flat = text.replace('\r', ' ').replace('\n', ' ').trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "...";
    }
}
