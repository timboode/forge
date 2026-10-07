package forge.llm;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import forge.GuiDesktop;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import forge.llm.action.GameAction;
import forge.llm.agent.AgentChoice;
import forge.llm.agent.AgentRequest;
import forge.llm.agent.DecisionAgent;
import forge.llm.agent.DecisionKind;
import forge.llm.agent.SummaryRequest;
import forge.llm.agent.stub.PassOnlyStubAgent;
import forge.llm.control.LlmPlayerConfig;
import forge.llm.control.LlmStats;
import forge.llm.control.LobbyPlayerLlm;
import forge.llm.run.GameLauncher;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;

/**
 * Plays complete games with the real engine: an LLM-controlled seat (driven by stub agents) against the
 * built-in AI. These guard the properties that matter for the integration: the offered actions are really
 * executable, a broken agent can never wedge a game, and the memory lifecycle reaches the agent.
 *
 * Needs the Forge resource files, so it must run with the working directory next to forge-gui.
 */
public class LlmGameIntegrationTest {
    private static final String PRECONS = "../forge-gui/res/quest/precons/";
    private static final String[][] DECK_PAIRS = {
            {"Aerodoom.dck", "Air Forces.dck"},
            {"Bait and Bludgeon.dck", "Spirit Gale.dck"},
    };
    private static final int TIMEOUT_SECONDS = 180;

    @BeforeClass
    public void initializeForge() {
        System.setProperty("java.awt.headless", "true");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
    }

    private record Played(Game game, LlmStats stats, GameLauncher.Result result) {
    }

    private Played play(DecisionAgent agent, LlmPlayerConfig cfg, String[] decks, long seed) {
        MyRandom.setRandom(new java.util.Random(seed));
        Deck d1 = DeckSerializer.fromFile(new File(PRECONS + decks[0]));
        Deck d2 = DeckSerializer.fromFile(new File(PRECONS + decks[1]));
        assertNotNull(d1, decks[0]);
        assertNotNull(d2, decks[1]);
        LobbyPlayerLlm llm = new LobbyPlayerLlm("LLM", agent, cfg);
        List<RegisteredPlayer> players = List.of(
                new RegisteredPlayer(d1).setPlayer(llm),
                new RegisteredPlayer(d2).setPlayer(GamePlayerUtil.createAiPlayer("AI", 1)));
        GameLauncher.Result result = GameLauncher.play(GameLauncher.newMatch(players), TIMEOUT_SECONDS);
        assertFalse(result.timedOut(), "game hit the " + TIMEOUT_SECONDS + "s limit - the LLM seat probably wedged it");
        assertTrue(result.game().isGameOver());
        return new Played(result.game(), llm.stats(), result);
    }

    @Test
    public void offeredActionsAreAlwaysExecutableAndNeverNeedTheAiFallback() {
        for (String[] decks : DECK_PAIRS) {
            AuditingAgent agent = new AuditingAgent();
            Played p = play(agent, new LlmPlayerConfig(), decks, 7);
            String ctx = decks[0] + " vs " + decks[1] + ": " + p.stats();
            assertTrue(p.stats().consultations.get() > 0, ctx);
            assertEquals(p.stats().failedExecutions.get(), 0, "an offered action failed to execute - " + ctx);
            assertEquals(p.stats().invalidAnswers.get(), 0, ctx);
            assertEquals(p.stats().fallbacksToAi.get(), 0, ctx);
        }
    }

    /**
     * The heuristic stub never activates abilities or aims at its own permanents; this agent does anything it
     * is offered. Every offered action must really play (engine refusals are detected by checking that
     * something actually changed), whatever the agent picks, across several boards.
     */
    @Test
    public void whateverTheAgentPicksFromTheOfferedActionsTheEnginePlaysIt() {
        for (String[] decks : DECK_PAIRS) {
            for (long seed = 1; seed <= 3; seed++) {
                Played p = play(new ChaosAgent(seed), new LlmPlayerConfig(), decks, 100 + seed);
                String ctx = decks[0] + " vs " + decks[1] + " seed " + seed + ": " + p.stats();
                assertTrue(p.stats().consultations.get() > 0, ctx);
                assertEquals(p.stats().failedExecutions.get(), 0, "an offered action could not be played - " + ctx);
                assertEquals(p.stats().invalidAnswers.get(), 0, ctx);
                assertEquals(p.stats().fallbacksToAi.get(), 0, ctx);
            }
        }
    }

    @Test
    public void agentIsNeverAskedWhenNothingButPassingIsPossible() {
        AuditingAgent agent = new AuditingAgent();
        Played p = play(agent, new LlmPlayerConfig(), DECK_PAIRS[0], 11);
        assertTrue(p.stats().autoPassedNoActions.get() > 0, "expected many windows with nothing to do: " + p.stats());
        for (AgentRequest r : agent.requests) {
            if (r.kind() == DecisionKind.PRIORITY) {
                assertTrue(AuditingAgent.hasRealOption(r), "PRIORITY request with only a pass option: " + r.prompt());
                assertEquals(r.options().get(0).type(), GameAction.Type.PASS);
                assertEquals(r.options().get(0).id(), 0);
            } else {
                assertFalse(r.options().isEmpty(), "ATTACK/BLOCK request without options");
            }
        }
    }

    @Test
    public void promptsCarryTheRequiredContextAndSessionsBehaveAsDesigned() {
        AuditingAgent agent = new AuditingAgent();
        play(agent, new LlmPlayerConfig(), DECK_PAIRS[0], 3);

        int newSessions = 0;
        boolean sawResponseSession = false;
        boolean sawMemoryInNewSession = false;
        for (AgentRequest r : agent.requests) {
            if (r.newSession()) {
                newSessions++;
                assertTrue(r.prompt().contains("# YOUR DECK") && r.prompt().contains("Main deck ("), "full decklist");
                assertTrue(r.prompt().contains("# MATCH LOG"), "full match log");
                assertTrue(r.prompt().contains("## Your hand"), "hand");
                assertTrue(r.prompt().contains("### OPPONENT"), "opponent board");
                if (r.prompt().contains("SUMMARY-")) {
                    sawMemoryInNewSession = true;
                }
            } else if (r.kind() == DecisionKind.PRIORITY || r.prompt().startsWith("# UPDATE")) {
                assertFalse(r.prompt().contains("# YOUR DECK"), "later prompts of a session must not repeat the decklist");
            }
            if (r.sessionKey().endsWith("-resp")) {
                sawResponseSession = true;
            }
        }
        assertTrue(newSessions >= 2, "expected at least two sessions (one per own turn)");
        assertTrue(agent.requests.stream().anyMatch(r -> !r.newSession()), "expected follow-up requests inside a session");
        assertTrue(sawResponseSession, "expected at least one response window on an opponent's turn");
        assertTrue(sawMemoryInNewSession, "the compressed previous turn must be passed into later turns");
        assertTrue(agent.summaryRequests.stream().anyMatch(s -> s.kind() == SummaryRequest.Kind.TURN), "turn summaries");
        assertTrue(agent.endedSessions.size() >= newSessions - 1, "sessions are closed when their turn ends");
    }

    @Test
    public void aThrowingAgentDegradesToTheBuiltInAiAndTheGameStillFinishes() {
        AtomicInteger calls = new AtomicInteger();
        DecisionAgent broken = new DecisionAgent() {
            @Override
            public AgentChoice decide(AgentRequest request) {
                calls.incrementAndGet();
                throw new IllegalStateException("model unreachable");
            }

            @Override
            public String summarize(SummaryRequest request) {
                throw new IllegalStateException("model unreachable");
            }
        };
        Played p = play(broken, new LlmPlayerConfig(), DECK_PAIRS[0], 5);
        assertTrue(calls.get() > 0);
        assertTrue(p.stats().fallbacksToAi.get() > 0, p.stats().toString());
    }

    @Test
    public void garbageAnswersAreRejectedRetriedAndThenReplacedByTheBuiltInAi() {
        DecisionAgent garbage = new DecisionAgent() {
            @Override
            public AgentChoice decide(AgentRequest request) {
                return AgentChoice.of(987654, "nonsense");
            }

            @Override
            public String summarize(SummaryRequest request) {
                return "";
            }
        };
        LlmPlayerConfig cfg = new LlmPlayerConfig();
        cfg.maxRetries = 2;
        Played p = play(garbage, cfg, DECK_PAIRS[0], 5);
        assertTrue(p.stats().invalidAnswers.get() >= 3, p.stats().toString());
        assertTrue(p.stats().fallbacksToAi.get() > 0, p.stats().toString());
    }

    /**
     * Opposing AIs that simulate copy the running game, and the copier keeps any lobby player that is a
     * LobbyPlayerAi. A copy is created while the real game is live and must not be handed to the model.
     */
    @Test
    public void aSimulationCopyOfTheLiveGameGetsThePlainAiController() {
        Deck d1 = DeckSerializer.fromFile(new File(PRECONS + DECK_PAIRS[0][0]));
        Deck d2 = DeckSerializer.fromFile(new File(PRECONS + DECK_PAIRS[0][1]));
        LobbyPlayerLlm llm = new LobbyPlayerLlm("LLM", new AuditingAgent(), new LlmPlayerConfig());
        forge.game.Match match = GameLauncher.newMatch(List.of(
                new RegisteredPlayer(d1).setPlayer(llm),
                new RegisteredPlayer(d2).setPlayer(GamePlayerUtil.createAiPlayer("AI", 1))));

        Game real = match.createGame();
        assertTrue(llmSeat(real).getController() instanceof forge.llm.control.PlayerControllerLlm);

        Game copy = match.createGame(); // while `real` is live: what a simulation copy looks like to the lobby player
        assertFalse(llmSeat(copy).getController() instanceof forge.llm.control.PlayerControllerLlm);
        assertTrue(llmSeat(copy).getController() instanceof forge.ai.PlayerControllerAi);

        real.setGameOver(forge.game.GameEndReason.Draw);
        Game next = match.createGame(); // the next real game of the match
        assertTrue(llmSeat(next).getController() instanceof forge.llm.control.PlayerControllerLlm);
    }

    private static forge.game.player.Player llmSeat(Game game) {
        return game.getPlayers().stream().filter(p -> p.getName().equals("LLM")).findFirst().orElseThrow();
    }

    /**
     * A model with a small context window overflows as a turn grows: the agent says so, the controller throws the
     * conversation away and carries on in a fresh session with a compact full prompt.
     */
    @Test
    public void anOverflowingConversationIsRestartedWithACompactPromptAndTheGameCarriesOn() {
        AuditingAgent inner = new AuditingAgent();
        DecisionAgent overflowing = new DecisionAgent() {
            @Override
            public AgentChoice decide(AgentRequest request) {
                if (!request.newSession()) {
                    throw new forge.llm.agent.ContextOverflowException("conversation too long");
                }
                return inner.decide(request);
            }

            @Override
            public String summarize(SummaryRequest request) {
                return inner.summarize(request);
            }
        };
        Played p = play(overflowing, new LlmPlayerConfig(), DECK_PAIRS[0], 17);

        assertTrue(p.stats().contextRestarts.get() > 0, p.stats().toString());
        assertEquals(p.stats().fallbacksToAi.get(), 0, "restarting is not a failure: " + p.stats());
        assertEquals(p.stats().failedExecutions.get(), 0, p.stats().toString());
        boolean sawCompact = false;
        for (AgentRequest r : inner.requests) {
            assertTrue(r.newSession(), "after the restart every request that got through was a full prompt");
            if (r.prompt().contains("only the most recent events")) {
                sawCompact = true;
                assertFalse(r.prompt().contains("## Cards still in your library"), "compact prompts drop the library listing");
                assertTrue(r.prompt().contains("# YOUR DECK"), "but keep the decklist");
            }
        }
        assertTrue(sawCompact, "restarted sessions must use the compact prompt");
    }

    /**
     * Commander: 40 life, a command zone, commander damage and tax, and several opponents - the table the Forge GUI
     * sets up for a multiplayer Commander game. Two seats are LLM-driven (stub agents), two are the built-in AI.
     */
    @Test
    public void aFourPlayerCommanderGameWithTwoLlmSeatsRunsToCompletionAndShowsCommanderInformation() {
        MyRandom.setRandom(new java.util.Random(21));
        List<forge.llm.run.Seat.Kind> kinds = List.of(forge.llm.run.Seat.Kind.LLM, forge.llm.run.Seat.Kind.AI,
                forge.llm.run.Seat.Kind.LLM, forge.llm.run.Seat.Kind.AI);
        List<forge.llm.run.Seat> seats = forge.llm.run.MatchSetup.seats(forge.llm.run.RunOptions.parse(new String[0]),
                forge.llm.run.Format.COMMANDER, kinds);
        AuditingAgent first = new AuditingAgent();
        AuditingAgent second = new AuditingAgent();
        forge.llm.run.MatchSetup.Table table = forge.llm.run.MatchSetup.table(forge.llm.run.Format.COMMANDER, seats,
                seat -> seat == seats.get(0) ? first : second, LlmPlayerConfig::new);

        GameLauncher.Result result = GameLauncher.play(GameLauncher.newMatch(table.players(), forge.llm.run.Format.COMMANDER), 420);

        assertFalse(result.timedOut(), "the game hit the time limit");
        assertTrue(result.game().isGameOver());
        for (forge.llm.control.LobbyPlayerLlm llm : table.llmSeats()) {
            LlmStats stats = llm.stats();
            assertTrue(stats.consultations.get() > 0, llm.getName() + " " + stats);
            assertEquals(stats.failedExecutions.get(), 0, "an offered action failed - " + llm.getName() + " " + stats);
            assertEquals(stats.invalidAnswers.get(), 0, llm.getName() + " " + stats);
            assertEquals(stats.fallbacksToAi.get(), 0, llm.getName() + " " + stats);
        }

        AgentRequest opening = first.requests.stream().filter(AgentRequest::newSession).findFirst().orElseThrow();
        assertTrue(opening.prompt().contains("Format: Commander"), "the format is named");
        assertTrue(opening.prompt().contains("Commander rules:"), "rules reminder for the model");
        assertTrue(opening.prompt().contains("There are 3 opponents"), "multiplayer hint");
        assertTrue(opening.prompt().contains("life 40"), "Commander starting life");
        assertEquals(opening.prompt().split("### OPPONENT", -1).length - 1, 3, "every opponent is shown");
        assertTrue(opening.prompt().contains("Commander: "), "each player's commander is listed with its zone and cast count");
        assertTrue(opening.prompt().contains("Commander:\n"), "the decklist has a commander section");

        // with several opponents an attacker can pick whom to attack: some attack request offered more than one defender
        int mostDefenders = 0;
        for (AgentRequest r : first.requests) {
            if (r.kind() == DecisionKind.ATTACK) {
                long defenders = r.options().stream().map(GameAction::describe).map(d -> d.substring(d.indexOf("-> ")))
                        .distinct().count();
                mostDefenders = (int) Math.max(mostDefenders, defenders);
            }
        }
        boolean everAttacked = first.requests.stream().anyMatch(r -> r.kind() == DecisionKind.ATTACK);
        assertTrue(!everAttacked || mostDefenders >= 2, "attack requests offered at most " + mostDefenders + " defender(s)");
    }

    /** The random-action agent at a Commander table: command-zone casts, commander tax and several defenders. */
    @Test
    public void whateverTheAgentPicksInACommanderGameTheEnginePlaysIt() {
        for (long seed = 31; seed <= 31; seed++) {
            final long chaosSeed = seed;
            MyRandom.setRandom(new java.util.Random(seed));
            List<forge.llm.run.Seat> seats = forge.llm.run.MatchSetup.seats(forge.llm.run.RunOptions.parse(new String[0]),
                    forge.llm.run.Format.COMMANDER, List.of(forge.llm.run.Seat.Kind.LLM, forge.llm.run.Seat.Kind.AI, forge.llm.run.Seat.Kind.AI));
            forge.llm.run.MatchSetup.Table table = forge.llm.run.MatchSetup.table(forge.llm.run.Format.COMMANDER, seats,
                    seat -> new ChaosAgent(chaosSeed), () -> {
                        LlmPlayerConfig cfg = new LlmPlayerConfig();
                        cfg.maxConsultationsPerTurn = 12; // a random player dithers; keep the game moving
                        return cfg;
                    });

            GameLauncher.Result result = GameLauncher.play(GameLauncher.newMatch(table.players(), forge.llm.run.Format.COMMANDER), 90);

            LlmStats stats = table.llmSeats().get(0).stats();
            assertTrue(stats.consultations.get() > 0, "seed " + seed + ": " + stats);
            assertEquals(stats.failedExecutions.get(), 0, "an offered action could not be played (seed " + seed + "): " + stats);
            // A random agent can still be told "no" - e.g. one blocker for an attacker that needs two - and then the
            // built-in AI decides: invalid answers and fallbacks are legitimate here. A Commander game with a passive
            // player can also simply go on for a long time, so the game need not finish; what must never happen is an
            // offered action that cannot be played, or a game that stops making progress.
            assertTrue(result.game().getPhaseHandler().getTurn() >= 8 || !result.timedOut(),
                    "seed " + seed + ": the game only reached turn " + result.game().getPhaseHandler().getTurn() + " - it looks stuck");
        }
    }

    @Test
    public void aPassOnlyAgentCanNeverWedgeTheGame() {
        Played p = play(new PassOnlyStubAgent(), new LlmPlayerConfig(), DECK_PAIRS[1], 9);
        assertEquals(p.stats().failedExecutions.get(), 0);
    }

    @Test
    public void theConsultationBudgetCapsModelCalls() {
        AuditingAgent agent = new AuditingAgent();
        LlmPlayerConfig cfg = new LlmPlayerConfig();
        cfg.maxConsultationsPerTurn = 2;
        Played p = play(agent, cfg, DECK_PAIRS[1], 13);
        // at most 2 consultations per turn, and at most one turn per player-turn-number
        int turns = p.game().getPhaseHandler().getTurn();
        assertTrue(p.stats().consultations.get() <= 2 * turns, p.stats() + " over " + turns + " turns");
        assertTrue(p.stats().autoPassedByGate.get() > 0, "the budget should have gated some windows: " + p.stats());
    }

    /**
     * The "Query MTG rules" option: the model may ask instead of choosing, the controller serves matching lines
     * from the rules files in the same conversation, and the model then plays normally.
     */
    @Test
    public void aRulesLookupIsServedFromTheRulesFilesAndTheGamePlaysOn() throws java.io.IOException {
        final java.nio.file.Path rulesFile = java.nio.file.Files.createTempFile("rules-int", ".txt");
        java.nio.file.Files.writeString(rulesFile, "701.26a A creature is summoning sick unless it has haste.\n");
        List<AgentRequest> seen = new java.util.ArrayList<>();
        AtomicInteger lookups = new AtomicInteger();
        DecisionAgent querying = new DecisionAgent() {
            @Override
            public AgentChoice decide(AgentRequest request) {
                seen.add(request);
                if (request.kind() == DecisionKind.PRIORITY && lookups.get() == 0 && request.options().size() > 1) {
                    int queryId = request.options().stream().mapToInt(GameAction::id).max().orElse(0) + 1;
                    lookups.incrementAndGet();
                    return AgentChoice.of(queryId, "need the rules").withSearchTerms(List.of("summoning sick", "haste"));
                }
                if (request.kind() == DecisionKind.PRIORITY) {
                    return AgentChoice.pass("stub: pass");
                }
                return AgentChoice.none("stub: none");
            }

            @Override
            public String summarize(SummaryRequest request) {
                return "";
            }
        };
        LlmPlayerConfig cfg = new LlmPlayerConfig();
        cfg.rules = forge.llm.rules.RulesLibrary.load(List.of(rulesFile), null);
        Played p = play(querying, cfg, DECK_PAIRS[0], 23);

        try {
            assertEquals(p.stats().rulesQueries.get(), 1, p.stats().toString());
            assertTrue(seen.stream().anyMatch(r -> r.prompt().contains("# RULES LOOKUP RESULT")),
                    "the lookup result was served in the conversation");
            assertTrue(seen.stream().anyMatch(r -> r.prompt().contains("701.26a")),
                    "the matching rules line reached the model");
            assertTrue(seen.stream().anyMatch(r -> r.prompt().contains("Query MTG rules")), "the option is offered");
            assertEquals(p.stats().invalidAnswers.get(), 0, p.stats().toString());
        } finally {
            java.nio.file.Files.deleteIfExists(rulesFile);
        }
    }

    @Test
    public void rulesLookupsAreCappedPerDecisionAndThenTheAiTakesOver() throws java.io.IOException {
        final java.nio.file.Path rulesFile = java.nio.file.Files.createTempFile("rules-cap", ".txt");
        java.nio.file.Files.writeString(rulesFile, "rule line about haste and summoning sickness.\n");
        AtomicInteger consults = new AtomicInteger();
        DecisionAgent asksTwiceThenPasses = new DecisionAgent() {
            @Override
            public AgentChoice decide(AgentRequest request) {
                if (request.kind() == DecisionKind.PRIORITY && request.options().size() > 1
                        && consults.incrementAndGet() <= 3) {
                    int queryId = request.options().stream().mapToInt(GameAction::id).max().orElse(0) + 1;
                    return AgentChoice.of(queryId, "ask").withSearchTerms(List.of("haste"));
                }
                if (request.kind() == DecisionKind.PRIORITY) {
                    return AgentChoice.pass("stub: pass");
                }
                return AgentChoice.none("stub: none");
            }

            @Override
            public String summarize(SummaryRequest request) {
                return "";
            }
        };
        LlmPlayerConfig cfg = new LlmPlayerConfig();
        cfg.maxRulesQueriesPerDecision = 1;
        cfg.rules = forge.llm.rules.RulesLibrary.load(List.of(rulesFile), null);
        Played p = play(asksTwiceThenPasses, cfg, DECK_PAIRS[0], 29);

        try {
            assertTrue(p.stats().rulesQueries.get() >= 1, p.stats().toString());
            assertTrue(p.stats().invalidAnswers.get() > 0, "the cap is reported as an invalid answer: " + p.stats());
            assertTrue(p.stats().fallbacksToAi.get() > 0, p.stats().toString());
        } finally {
            java.nio.file.Files.deleteIfExists(rulesFile);
        }
    }
}
