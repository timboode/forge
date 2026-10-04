package forge.llm.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;

/**
 * Decorator that appends every request, answer and summary to a writer. This is the main way to see exactly
 * what a model would be shown, and what it answered.
 */
public final class RecordingAgent implements DecisionAgent {
    private final DecisionAgent delegate;
    private final Writer out;

    public RecordingAgent(DecisionAgent delegate, Writer out) {
        this(delegate, out, "");
    }

    /** @param header written once at the top, e.g. the standing instructions the agent works under */
    public RecordingAgent(DecisionAgent delegate, Writer out, String header) {
        this.delegate = delegate;
        this.out = out;
        if (!header.isEmpty()) {
            write(header);
        }
    }

    @Override
    public AgentChoice decide(AgentRequest request) {
        write("\n======== REQUEST " + request.kind() + " | session " + request.sessionKey()
                + (request.newSession() ? " (NEW)" : "") + " ========\n" + request.prompt() + "\n");
        AgentChoice choice;
        try {
            choice = delegate.decide(request);
        } catch (RuntimeException e) {
            write("-------- AGENT ERROR: " + e + "\n");
            throw e;
        }
        write("-------- ANSWER: " + (choice == null ? "(none)" : choice.actionIds() + "  reasoning: " + choice.reasoning()) + "\n");
        return choice;
    }

    @Override
    public String summarize(SummaryRequest request) {
        write("\n======== SUMMARIZE " + request.kind() + " (" + request.playerName() + ") ========\n"
                + request.transcript() + "\n");
        String summary = delegate.summarize(request);
        write("-------- SUMMARY:\n" + summary + "\n");
        return summary;
    }

    @Override
    public void endSession(String sessionKey) {
        write("======== END SESSION " + sessionKey + " ========\n");
        delegate.endSession(sessionKey);
    }

    private synchronized void write(String s) {
        try {
            out.write(s);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
