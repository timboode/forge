package forge.llm.context;

import java.util.ArrayList;
import java.util.List;

import org.tinylog.Logger;

import forge.llm.agent.DecisionAgent;
import forge.llm.agent.SummaryRequest;

/**
 * Owns the memory lifecycle of one LLM player:
 *
 * <ul>
 *   <li>During the player's own turn one session stays open, so the model keeps full context as the turn
 *       progresses. Every decision and its reasoning is appended to the turn transcript.</li>
 *   <li>When the turn ends, the transcript is compressed into {@code previousTurnSummary} (replacing the
 *       one from the turn before; the old one is handed to the summarizer so memory carries forward).</li>
 *   <li>During opponents' turns, instant-speed decisions go to a separate transcript and responses use
 *       sessions that only know the last-turn summary.</li>
 *   <li>When the player's next turn begins, that instant-speed transcript is compressed into
 *       {@code instantSummary}; both summaries then go into the first prompt of the new turn.</li>
 * </ul>
 *
 * No game types in here: it deals in strings and the {@link DecisionAgent} so it can be tested in isolation.
 */
public final class ContextManager {
    private static final int FALLBACK_SUMMARY_CHARS = 1200;

    private final String playerName;
    private final DecisionAgent agent;

    private String previousTurnSummary = "";
    private String instantSummary = "";
    private final List<String> turnTranscript = new ArrayList<>();
    private final List<String> instantTranscript = new ArrayList<>();
    private SessionState current;
    private int summariesMade;

    public ContextManager(String playerName, DecisionAgent agent) {
        this.playerName = playerName;
        this.agent = agent;
    }

    public Memory memory() {
        return new Memory(previousTurnSummary, instantSummary);
    }

    public int summariesMade() {
        return summariesMade;
    }

    /**
     * Throws the open conversation away and starts a new one under the same key, for when the agent could not
     * continue it (its context window overflowed). The next prompt for it must be a full one.
     */
    public SessionState restartSession(String key) {
        closeCurrentSession();
        current = new SessionState(key);
        return current;
    }

    /** The session for {@code key}: the open one if it matches, otherwise a fresh one (closing the old). */
    public SessionState session(String key) {
        if (current != null && current.key().equals(key)) {
            return current;
        }
        closeCurrentSession();
        current = new SessionState(key);
        return current;
    }

    /** Appends a line to the transcript of the own turn or of the opponents' turns. */
    public void record(boolean ownTurn, String line) {
        (ownTurn ? turnTranscript : instantTranscript).add(line);
    }

    /** The player's turn begins: compress what they did at instant speed since their last turn. */
    public void onOwnTurnBegan() {
        closeCurrentSession();
        turnTranscript.clear();
        if (instantTranscript.isEmpty()) {
            instantSummary = "";
            return;
        }
        instantSummary = compress(SummaryRequest.Kind.INSTANT_ACTIONS, String.join("\n", instantTranscript), "");
        instantTranscript.clear();
    }

    /** The player's turn is over: compress it into the memory for next time. */
    public void onOwnTurnEnded() {
        closeCurrentSession();
        if (turnTranscript.isEmpty() && instantSummary.isEmpty()) {
            return; // nothing happened that is worth a model call; last turn's summary stays as it was
        }
        StringBuilder input = new StringBuilder();
        if (!instantSummary.isEmpty()) {
            input.append("[Instant-speed activity during the opponents' turns before this turn]\n")
                    .append(instantSummary).append("\n\n");
        }
        input.append("[This turn]\n").append(String.join("\n", turnTranscript));
        previousTurnSummary = compress(SummaryRequest.Kind.TURN, input.toString(), previousTurnSummary);
        instantSummary = ""; // folded into the turn summary above
        turnTranscript.clear();
    }

    /** An opponent's turn is over: its response session (if any) will not be used again. */
    public void onOtherTurnEnded() {
        closeCurrentSession();
    }

    private void closeCurrentSession() {
        if (current != null) {
            try {
                agent.endSession(current.key());
            } catch (RuntimeException e) {
                Logger.warn(e, "agent.endSession failed for {}", current.key());
            }
            current = null;
        }
    }

    private String compress(SummaryRequest.Kind kind, String transcript, String prior) {
        summariesMade++;
        try {
            String s = agent.summarize(new SummaryRequest(kind, playerName, transcript, prior));
            if (s != null && !s.isBlank()) {
                return s.trim();
            }
        } catch (RuntimeException e) {
            Logger.warn(e, "summarizer failed; keeping the raw tail of the transcript instead");
        }
        // The summary is memory, not a decision: losing detail beats losing the game, so keep the tail.
        return transcript.length() <= FALLBACK_SUMMARY_CHARS ? transcript
                : "..." + transcript.substring(transcript.length() - FALLBACK_SUMMARY_CHARS);
    }
}
