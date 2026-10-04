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
}
