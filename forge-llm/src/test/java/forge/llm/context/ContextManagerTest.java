package forge.llm.context;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.llm.agent.AgentChoice;
import forge.llm.agent.AgentRequest;
import forge.llm.agent.DecisionAgent;
import forge.llm.agent.SummaryRequest;

public class ContextManagerTest {

    /** Records what it was asked and returns predictable summaries. */
    private static final class FakeAgent implements DecisionAgent {
        final List<SummaryRequest> summaries = new ArrayList<>();
        final List<String> endedSessions = new ArrayList<>();
        boolean failSummaries;

        @Override
        public AgentChoice decide(AgentRequest request) {
            return AgentChoice.pass("");
        }

        @Override
        public String summarize(SummaryRequest request) {
            summaries.add(request);
            if (failSummaries) {
                throw new IllegalStateException("model unavailable");
            }
            return "S(" + request.kind() + "#" + summaries.size() + ")";
        }

        @Override
        public void endSession(String sessionKey) {
            endedSessions.add(sessionKey);
        }
    }

    private FakeAgent agent;
    private ContextManager ctx;

    @BeforeMethod
    public void setUp() {
        agent = new FakeAgent();
        ctx = new ContextManager("Alice", agent);
    }

    @Test
    public void sameKeyKeepsTheSessionOpenAndNewKeyClosesTheOld() {
        SessionState a = ctx.session("T1-own");
        assertSame(ctx.session("T1-own"), a);
        SessionState b = ctx.session("T2-resp");
        assertNotSame(a, b);
        assertEquals(agent.endedSessions, List.of("T1-own"));
    }

    @Test
    public void ownTurnIsCompressedAtTurnEndAndCarriedForward() {
        ctx.onOwnTurnBegan();
        ctx.session("T1-own");
        ctx.record(true, "T1 Main: Play land");
        ctx.onOwnTurnEnded();

        assertEquals(ctx.memory().previousTurnSummary(), "S(TURN#1)");
        assertEquals(agent.summaries.get(0).kind(), SummaryRequest.Kind.TURN);
        assertTrue(agent.summaries.get(0).transcript().contains("T1 Main: Play land"));
        assertEquals(agent.summaries.get(0).priorSummary(), "");
        assertTrue(agent.endedSessions.contains("T1-own"));

        ctx.onOwnTurnBegan();
        ctx.record(true, "T3 Main: Cast Bear");
        ctx.onOwnTurnEnded();
        assertEquals(ctx.memory().previousTurnSummary(), "S(TURN#2)");
        assertEquals(agent.summaries.get(1).priorSummary(), "S(TURN#1)", "the older summary is handed on so memory carries forward");
        assertTrue(!agent.summaries.get(1).transcript().contains("Play land"), "only the new turn is in the transcript");
    }

    @Test
    public void instantActionsAreCompressedWhenTheNextOwnTurnBegins() {
        ctx.record(false, "T2 End step: Cast Counterspell");
        assertEquals(ctx.memory().instantSummary(), "", "nothing is compressed before the turn begins");

        ctx.onOwnTurnBegan();
        assertEquals(ctx.memory().instantSummary(), "S(INSTANT_ACTIONS#1)");
        assertTrue(agent.summaries.get(0).transcript().contains("Cast Counterspell"));

        // folded into the turn summary when that turn ends
        ctx.onOwnTurnEnded();
        assertEquals(ctx.memory().instantSummary(), "");
        assertTrue(agent.summaries.get(1).transcript().contains("S(INSTANT_ACTIONS#1)"));
    }

    @Test
    public void aTurnInWhichNothingWasRecordedCostsNoModelCallAndKeepsTheOldMemory() {
        ctx.onOwnTurnBegan();
        ctx.record(true, "T1 Main: Play land");
        ctx.onOwnTurnEnded();
        assertEquals(agent.summaries.size(), 1);

        ctx.onOwnTurnBegan();
        ctx.onOwnTurnEnded(); // the player had nothing to do and was never asked anything
        assertEquals(agent.summaries.size(), 1, "an empty transcript is not worth a model call");
        assertEquals(ctx.memory().previousTurnSummary(), "S(TURN#1)", "the last real summary is kept");
    }

    @Test
    public void noInstantActivityMeansNoInstantSummaryAndNoModelCall() {
        ctx.onOwnTurnBegan();
        assertEquals(ctx.memory().instantSummary(), "");
        assertTrue(agent.summaries.isEmpty());
    }

    @Test
    public void failingSummarizerFallsBackToTheRawTail() {
        agent.failSummaries = true;
        ctx.onOwnTurnBegan();
        ctx.record(true, "T1 Main: Play land");
        ctx.onOwnTurnEnded();
        assertTrue(ctx.memory().previousTurnSummary().contains("Play land"));
    }
}
