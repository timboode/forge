package forge.llm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import forge.llm.action.AttackAction;
import forge.llm.action.BlockAction;
import forge.llm.action.GameAction;
import forge.llm.agent.AgentChoice;
import forge.llm.agent.AgentRequest;
import forge.llm.agent.DecisionAgent;
import forge.llm.agent.SummaryRequest;

/**
 * Picks legal options at random - including activated abilities and targets aimed at its own permanents,
 * which the heuristic stub never touches. If an option that was offered as legal cannot actually be played,
 * the controller counts it as a failed execution, which is what the tests using this agent assert on.
 */
final class ChaosAgent implements DecisionAgent {
    private final Random random;

    ChaosAgent(long seed) {
        this.random = new Random(seed);
    }

    @Override
    public AgentChoice decide(AgentRequest request) {
        List<GameAction> options = request.options();
        switch (request.kind()) {
            case PRIORITY: {
                List<GameAction> real = new ArrayList<>();
                for (GameAction a : options) {
                    if (a.type() != GameAction.Type.PASS) {
                        real.add(a);
                    }
                }
                if (real.isEmpty() || random.nextInt(4) == 0) {
                    return AgentChoice.pass("chaos: pass");
                }
                return AgentChoice.of(real.get(random.nextInt(real.size())).id(), "chaos: random option");
            }
            case ATTACK: {
                Set<Integer> attackers = new HashSet<>();
                List<Integer> ids = new ArrayList<>();
                for (GameAction a : options) {
                    AttackAction atk = (AttackAction) a;
                    if (random.nextBoolean() && attackers.add(atk.attacker().getId())) {
                        ids.add(a.id());
                    }
                }
                return AgentChoice.of(ids, "chaos: random attackers");
            }
            default: {
                Set<Integer> blockers = new HashSet<>();
                List<Integer> ids = new ArrayList<>();
                for (GameAction a : options) {
                    BlockAction blk = (BlockAction) a;
                    if (random.nextBoolean() && blockers.add(blk.blocker().getId())) {
                        ids.add(a.id());
                    }
                }
                return AgentChoice.of(ids, "chaos: random blockers");
            }
        }
    }

    @Override
    public String summarize(SummaryRequest request) {
        return "chaos summary";
    }
}
