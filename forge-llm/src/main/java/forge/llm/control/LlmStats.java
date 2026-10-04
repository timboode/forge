package forge.llm.control;

import java.util.concurrent.atomic.AtomicInteger;

/** Counters so a test run can show how the player behaved. Thread-safe. */
public final class LlmStats {
    public final AtomicInteger consultations = new AtomicInteger();
    public final AtomicInteger autoPassedNoActions = new AtomicInteger();
    public final AtomicInteger autoPassedByGate = new AtomicInteger();
    public final AtomicInteger invalidAnswers = new AtomicInteger();
    public final AtomicInteger fallbacksToAi = new AtomicInteger();
    public final AtomicInteger failedExecutions = new AtomicInteger();
    public final AtomicInteger summaries = new AtomicInteger();
    public final AtomicInteger contextRestarts = new AtomicInteger();

    @Override
    public String toString() {
        return "consultations=" + consultations + ", autoPassedNoActions=" + autoPassedNoActions
                + ", autoPassedByGate=" + autoPassedByGate + ", invalidAnswers=" + invalidAnswers
                + ", fallbacksToAi=" + fallbacksToAi + ", failedExecutions=" + failedExecutions
                + ", summaries=" + summaries
                + ", contextRestarts=" + contextRestarts;
    }
}
