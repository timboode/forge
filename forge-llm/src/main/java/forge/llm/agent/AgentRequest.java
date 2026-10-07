package forge.llm.agent;

import java.util.List;

import forge.llm.action.GameAction;

/**
 * One question put to the agent.
 *
 * @param sessionKey stable id of the conversation this belongs to (one per own turn; one per opponent turn
 *                   for responses). A real implementation keeps the model's conversation open per key.
 * @param newSession true on the first request of a session: {@code prompt} then carries the complete
 *                   context (decklist, summaries, full log...). Otherwise it is an incremental update.
 * @param prompt     everything the model should read, already rendered as text
 * @param options    the legal choices; the agent answers with their ids. For PRIORITY the pass option
 *                   (id 0) is included.
 */
public record AgentRequest(String sessionKey, boolean newSession, DecisionKind kind, String prompt,
                           List<GameAction> options) {
}
