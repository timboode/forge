package forge.llm.agent;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decorator that bounds how long the game thread can be held by an agent. A model call that hangs must not
 * hang the game: on timeout the call is abandoned (its worker is a daemon thread and is interrupted) and a
 * {@link DecisionTimeoutException} is thrown, which the controller treats like any other agent failure.
 */
public final class TimeLimitedAgent implements DecisionAgent {
    /** Thrown when the delegate does not answer in time. */
    public static final class DecisionTimeoutException extends RuntimeException {
        public DecisionTimeoutException(String message) {
            super(message);
        }
    }

    private final DecisionAgent delegate;
    private final long timeoutSeconds;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "LLM agent call");
        t.setDaemon(true);
        return t;
    });

    public TimeLimitedAgent(DecisionAgent delegate, long timeoutSeconds) {
        this.delegate = delegate;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public AgentChoice decide(AgentRequest request) {
        return bounded("decide", () -> delegate.decide(request));
    }

    @Override
    public String summarize(SummaryRequest request) {
        return bounded("summarize", () -> delegate.summarize(request));
    }

    @Override
    public void endSession(String sessionKey) {
        delegate.endSession(sessionKey);
    }

    private <T> T bounded(String what, java.util.concurrent.Callable<T> call) {
        final Future<T> future = pool.submit(call);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new DecisionTimeoutException("agent " + what + " did not answer within " + timeoutSeconds + "s");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DecisionTimeoutException("interrupted while waiting for the agent to " + what);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("agent " + what + " failed", e.getCause());
        }
    }
}
