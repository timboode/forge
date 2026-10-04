package forge.llm.agent;

/**
 * Ask the agent to compress a block of transcript into a short strategic memory.
 *
 * @param kind         TURN: a complete own turn; INSTANT_ACTIONS: actions taken during opponents' turns
 * @param transcript   the raw material to compress
 * @param priorSummary the previous summary of the same kind (may be empty), so memory can carry forward
 */
public record SummaryRequest(Kind kind, String playerName, String transcript, String priorSummary) {
    public enum Kind { TURN, INSTANT_ACTIONS }
}
