package forge.llm.openai;

/**
 * Anything that went wrong talking to the raw OpenAI-compatible inference provider. A {@link RuntimeException}:
 * the controller catches it, falls back to the built-in AI for the decision and keeps the game going.
 */
public final class RawInferenceException extends RuntimeException {
    public RawInferenceException(String message) {
        super(message);
    }

    public RawInferenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
