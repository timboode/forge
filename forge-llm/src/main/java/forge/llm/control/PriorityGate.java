package forge.llm.control;

import forge.game.phase.PhaseType;

/**
 * Hard logic that decides, before any model is invoked, whether a priority window is worth a consultation.
 * Pure function of its inputs so it can be unit tested without a game.
 *
 * The caller has already established that the player has at least one legal action besides passing; this
 * only decides whether the situation is one where the agent should be asked about it.
 */
public final class PriorityGate {
    private PriorityGate() {
    }

    public record Verdict(boolean consult, String reason) {
    }

    public record Situation(boolean ownTurn, PhaseType phase, boolean stackEmpty, boolean stackTopIsMine,
                            int consultationsThisTurn) {
    }

    public static Verdict evaluate(Situation s, LlmPlayerConfig cfg) {
        if (s.consultationsThisTurn() >= cfg.maxConsultationsPerTurn) {
            return new Verdict(false, "consultation budget for this turn is used up");
        }
        if (!s.stackEmpty() && s.stackTopIsMine() && cfg.autoPassOnOwnStackItem) {
            return new Verdict(false, "own spell/ability on top of the stack: let it resolve");
        }
        if (s.ownTurn()) {
            return cfg.ownTurnWindows.contains(s.phase())
                    ? new Verdict(true, "own turn")
                    : new Verdict(false, "own turn, but " + s.phase() + " is not a consultation window");
        }
        // an opponent's turn
        if (!s.stackEmpty() && !s.stackTopIsMine()) {
            return cfg.respondToOpponentStackItems
                    ? new Verdict(true, "opponent's spell/ability on the stack")
                    : new Verdict(false, "responding to opponent stack items is disabled");
        }
        if (s.stackEmpty() && cfg.opponentTurnWindows.contains(s.phase())) {
            return new Verdict(true, "opponent's turn, " + s.phase() + " window");
        }
        return new Verdict(false, "opponent's turn, nothing worth waking the agent for in " + s.phase());
    }
}
