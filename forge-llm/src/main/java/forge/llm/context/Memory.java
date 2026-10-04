package forge.llm.context;

/**
 * The compressed context carried between turns.
 *
 * @param previousTurnSummary compressed copy of the player's last own turn
 * @param instantSummary      compressed instant-speed activity from the opponents' turns since then
 */
public record Memory(String previousTurnSummary, String instantSummary) {
    public static final Memory NONE = new Memory("", "");
}
