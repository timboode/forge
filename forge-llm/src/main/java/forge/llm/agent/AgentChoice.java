package forge.llm.agent;

import java.util.List;

/**
 * The agent's answer: PRIORITY expects exactly one id (0 = pass); ATTACK/BLOCK take any number of ids
 * (an empty list means no attackers / no blockers).
 */
public record AgentChoice(List<Integer> actionIds, String reasoning) {

    public static AgentChoice of(int id, String reasoning) {
        return new AgentChoice(List.of(id), reasoning);
    }

    public static AgentChoice of(List<Integer> ids, String reasoning) {
        return new AgentChoice(List.copyOf(ids), reasoning);
    }

    public static AgentChoice pass(String reasoning) {
        return of(0, reasoning);
    }

    public static AgentChoice none(String reasoning) {
        return new AgentChoice(List.of(), reasoning);
    }
}
