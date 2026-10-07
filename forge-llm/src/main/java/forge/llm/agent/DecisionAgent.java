package forge.llm.agent;

/**
 * The seam between the game and the LLM. Everything Forge-side talks to this interface only, so the
 * stub implementations and a real opencode-backed one are interchangeable.
 *
 * Implementations are called on the game thread and may block; they should enforce their own timeout and
 * throw (or return an invalid choice) on failure - the caller falls back to the built-in AI.
 */
public interface DecisionAgent {

    AgentChoice decide(AgentRequest request);

    /** Compress transcript into a short memory. Callers fall back to a naive truncation if this throws. */
    String summarize(SummaryRequest request);

    /** The conversation with this key will not be used again; implementations may free resources. */
    default void endSession(String sessionKey) {
    }
}
