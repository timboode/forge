package forge.llm.agent;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

import java.util.List;

import org.testng.annotations.Test;

public class TimeLimitedAgentTest {

    private static AgentRequest request() {
        return new AgentRequest("k", true, DecisionKind.PRIORITY, "prompt", List.of());
    }

    private static DecisionAgent agent(long sleepMillis, RuntimeException toThrow) {
        return new DecisionAgent() {
            @Override
            public AgentChoice decide(AgentRequest r) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (toThrow != null) {
                    throw toThrow;
                }
                return AgentChoice.pass("ok");
            }

            @Override
            public String summarize(SummaryRequest r) {
                return "sum";
            }
        };
    }

    @Test
    public void fastAnswersPassThrough() {
        assertEquals(new TimeLimitedAgent(agent(0, null), 5).decide(request()).reasoning(), "ok");
        assertEquals(new TimeLimitedAgent(agent(0, null), 5).summarize(new SummaryRequest(SummaryRequest.Kind.TURN, "p", "t", "")), "sum");
    }

    @Test
    public void aHungAgentIsAbandonedInsteadOfHoldingTheGameThread() {
        long start = System.currentTimeMillis();
        assertThrows(TimeLimitedAgent.DecisionTimeoutException.class, () -> new TimeLimitedAgent(agent(30_000, null), 1).decide(request()));
        assertEquals(System.currentTimeMillis() - start < 10_000, true, "should give up after about a second");
    }

    @Test
    public void agentExceptionsKeepTheirType() {
        assertThrows(IllegalStateException.class, () -> new TimeLimitedAgent(agent(0, new IllegalStateException("boom")), 5).decide(request()));
    }
}
