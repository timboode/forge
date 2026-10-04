package forge.llm.opencode;

/** The opencode server could not be started, was unreachable, or reported an error. */
public class OpencodeException extends RuntimeException {
    public OpencodeException(String message) {
        super(message);
    }

    public OpencodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
