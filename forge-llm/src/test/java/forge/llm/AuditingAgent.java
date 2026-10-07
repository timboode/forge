package forge.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import forge.llm.action.GameAction;
import forge.llm.agent.AgentChoice;
import forge.llm.agent.AgentRequest;
import forge.llm.agent.DecisionAgent;
import forge.llm.agent.SummaryRequest;
import forge.llm.agent.stub.HeuristicStubAgent;

/** Wraps the heuristic stub and keeps every request/summary so tests can assert on what the agent was shown. */
final class AuditingAgent implements DecisionAgent {
    private final DecisionAgent delegate = new HeuristicStubAgent();
    final List<AgentRequest> requests = Collections.synchronizedList(new ArrayList<>());
    final List<SummaryRequest> summaryRequests = Collections.synchronizedList(new ArrayList<>());
    final List<String> endedSessions = Collections.synchronizedList(new ArrayList<>());

    @Override
    public AgentChoice decide(AgentRequest request) {
        requests.add(request);
        return delegate.decide(request);
    }

    @Override
    public String summarize(SummaryRequest request) {
        summaryRequests.add(request);
        return "SUMMARY-" + summaryRequests.size() + "[" + request.kind() + "]";
    }

    @Override
    public void endSession(String sessionKey) {
        endedSessions.add(sessionKey);
    }

    static boolean hasRealOption(AgentRequest r) {
        return r.options().stream().anyMatch(o -> o.type() != GameAction.Type.PASS);
    }
}
