package forge.llm.agent;

/**
 * Thrown by an agent when a request cannot be sent because the model's context window cannot hold it - either
 * the conversation has grown too long, or the prompt is too big for an empty one.
 *
 * The controller reacts by throwing the conversation away and starting a fresh session with a <em>compact</em>
 * full prompt (shorter match log, no library listing). If a compact prompt does not fit either, the decision is
 * left to the built-in AI.
 */
public final class ContextOverflowException extends RuntimeException {
    public ContextOverflowException(String message) {
        super(message);
    }
}
