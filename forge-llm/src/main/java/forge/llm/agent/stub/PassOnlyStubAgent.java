package forge.llm.agent.stub;

import forge.llm.agent.AgentChoice;
import forge.llm.agent.AgentRequest;
import forge.llm.agent.DecisionAgent;
import forge.llm.agent.SummaryRequest;

/** Always passes / never attacks / never blocks. Handy to test that gating and fallbacks do not hang a game. */
public final class PassOnlyStubAgent implements DecisionAgent {
    @Override
    public AgentChoice decide(AgentRequest request) {
        return switch (request.kind()) {
            case PRIORITY -> AgentChoice.pass("stub: always pass");
            case ATTACK, BLOCK -> AgentChoice.none("stub: no attackers/blockers");
        };
    }

    @Override
    public String summarize(SummaryRequest request) {
        return "[pass-only stub summary]";
    }
}
