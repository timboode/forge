package forge.llm.agent;

public enum DecisionKind {
    /** Pick one action (or pass) while holding priority. */
    PRIORITY,
    /** Pick any number of attacks (none = no attack). */
    ATTACK,
    /** Pick any number of blocks (none = no block). */
    BLOCK
}
