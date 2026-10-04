package forge.llm.control;

import java.util.EnumSet;
import java.util.Set;

import forge.game.phase.PhaseType;

/** Tunables for one LLM-controlled player. Defaults are sensible for an MVP; all fields are mutable. */
public final class LlmPlayerConfig {

    /** Hard cap on agent consultations in one turn (anyone's); beyond it the player just passes / uses the AI. */
    public int maxConsultationsPerTurn = 80;

    /** How many times an invalid answer is sent back to the agent before falling back to the built-in AI. */
    public int maxRetries = 1;

    /**
     * Wall-clock limit for one agent call (a decision or a summary). A call that exceeds it is abandoned and
     * treated as an agent failure, so a hung model can never hang the game. 0 = no limit.
     */
    public int decisionTimeoutSeconds = 180;

    /**
     * When the top of the stack is the player's own spell/ability, do not ask the agent whether to respond to
     * it - just let it resolve. Saves a model call after every spell cast.
     */
    public boolean autoPassOnOwnStackItem = true;

    /** On an opponent's turn, consult the agent whenever something of the opponent's is on the stack. */
    public boolean respondToOpponentStackItems = true;

    /**
     * On an opponent's turn with an empty stack, the only steps worth waking the agent for (and only if it
     * has a legal instant-speed action there).
     */
    public Set<PhaseType> opponentTurnWindows = EnumSet.of(
            PhaseType.COMBAT_DECLARE_ATTACKERS, PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.END_OF_TURN);

    /** On the player's own turn, steps in which the agent is consulted when it has a legal action. Default: all. */
    public Set<PhaseType> ownTurnWindows = EnumSet.allOf(PhaseType.class);

    /** Longest match log (in lines) shown in a full prompt; 0 = unlimited. */
    public int maxLogLines = 0;
}
