package forge.llm.agent;

import java.util.List;

/**
 * The agent's answer: PRIORITY expects exactly one id (0 = pass); ATTACK/BLOCK take any number of ids
 * (an empty list means no attackers / no blockers). {@code searchTerms} carries the search phrases when
 * the answer uses the "Query MTG rules" option.
 */
public record AgentChoice(List<Integer> actionIds, String reasoning, List<String> searchTerms) {

    public AgentChoice(List<Integer> actionIds, String reasoning) {
        this(actionIds, reasoning, List.of());
    }

    public static AgentChoice of(int id, String reasoning) {
        return new AgentChoice(List.of(id), reasoning, List.of());
    }

    public static AgentChoice of(List<Integer> ids, String reasoning) {
        return new AgentChoice(List.copyOf(ids), reasoning, List.of());
    }

    public static AgentChoice pass(String reasoning) {
        return of(0, reasoning);
    }

    public static AgentChoice none(String reasoning) {
        return new AgentChoice(List.of(), reasoning, List.of());
    }

    public AgentChoice withSearchTerms(List<String> terms) {
        return new AgentChoice(actionIds, reasoning, List.copyOf(terms));
    }

    /** Whether this answer asks for a rules lookup through the option with the given id. */
    public boolean isRulesQuery(int queryOptionId) {
        return !searchTerms.isEmpty() && actionIds.equals(List.of(queryOptionId));
    }
}
