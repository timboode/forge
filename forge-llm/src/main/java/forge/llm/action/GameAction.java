package forge.llm.action;

/**
 * One concrete thing the LLM may choose to do at a decision point. Instances are produced by the
 * enumerators from live game state, so every action is (to the best of the engine's knowledge) legal.
 * The agent only ever refers to an action by its {@link #id()}.
 */
public abstract class GameAction {

    public enum Type { PASS, PLAY_LAND, CAST_SPELL, ACTIVATE_ABILITY, ATTACK, BLOCK }

    /** Who a targeted action points at; lets simple (stub) agents avoid hurting themselves. */
    public enum TargetKind { NONE, OWN, OPPONENT, NEUTRAL }

    private int id = -1;

    public abstract Type type();

    /** One-line description for the prompt, without the id prefix. */
    public abstract String describe();

    /** Rough "how much is this worth doing" number (mana value for spells). Only used by stub agents. */
    public int weight() {
        return 0;
    }

    public TargetKind targetKind() {
        return TargetKind.NONE;
    }

    /**
     * Stable identity of the action across re-enumerations in the same game state, used to remember
     * actions that failed to execute so they are not offered again in a loop.
     */
    public abstract String key();

    public final int id() {
        return id;
    }

    final void assignId(int id) {
        this.id = id;
    }

    public final String toPromptLine() {
        return "[" + id + "] " + describe();
    }

    @Override
    public String toString() {
        return toPromptLine();
    }
}
