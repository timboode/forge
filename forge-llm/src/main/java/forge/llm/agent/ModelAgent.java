package forge.llm.agent;

/**
 * A {@link DecisionAgent} backed by a real model: it knows its context window, can name the standing
 * instructions it works under (for transcripts and diagnostics), and may hold resources - a server,
 * open conversations - that {@link #close()} frees.
 */
public interface ModelAgent extends DecisionAgent, AutoCloseable {
    /** The model's context window in tokens; prompts are sized against it. */
    int contextTokens();

    /** The standing instructions the model works under, for transcript headers and diagnostics. */
    String systemPrompt();
}
