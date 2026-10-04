package forge.llm.context;

import java.util.HashSet;
import java.util.Set;

/** Book-keeping for one agent conversation (see {@link forge.llm.agent.AgentRequest#sessionKey()}). */
public final class SessionState {
    private final String key;
    private int decisions;
    private int logCursor;
    private final Set<String> cardTextSent = new HashSet<>();

    private int savedDecisions;
    private int savedLogCursor;
    private Set<String> savedCardTextSent = Set.of();

    SessionState(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** True until a prompt of this session has been delivered to the agent. */
    public boolean isNew() {
        return decisions == 0;
    }

    public int decisionNumber() {
        return decisions + 1;
    }

    public void promptBuilt(int newLogCursor) {
        decisions++;
        logCursor = newLogCursor;
    }

    /** Raw game-log index up to which this session has already been told what happened. */
    public int logCursor() {
        return logCursor;
    }

    /** Card names whose oracle text this session has already received. */
    public Set<String> cardTextSent() {
        return cardTextSent;
    }

    /**
     * Call before building a prompt. If delivering that prompt then fails, {@link #rollback()} makes the
     * session behave as if it had never been built, so the next attempt sends the same context again
     * instead of an update to a conversation the model never received.
     */
    public void checkpoint() {
        savedDecisions = decisions;
        savedLogCursor = logCursor;
        savedCardTextSent = new HashSet<>(cardTextSent);
    }

    public void rollback() {
        decisions = savedDecisions;
        logCursor = savedLogCursor;
        cardTextSent.clear();
        cardTextSent.addAll(savedCardTextSent);
    }
}
