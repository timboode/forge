package forge.llm.agent.stub;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.llm.action.AttackAction;
import forge.llm.action.BlockAction;
import forge.llm.action.GameAction;
import forge.llm.agent.AgentChoice;
import forge.llm.agent.AgentRequest;
import forge.llm.agent.DecisionAgent;
import forge.llm.agent.SummaryRequest;

/**
 * Stand-in for the LLM so the whole pipeline can be exercised without one. It plays lands, casts the most
 * expensive spell it can (never aiming at its own stuff, never activating abilities), attacks when that
 * looks safe and blocks when that looks profitable. Deliberately simple and deterministic - it exists to
 * drive the plumbing, not to play well.
 */
public final class HeuristicStubAgent implements DecisionAgent {

    @Override
    public AgentChoice decide(AgentRequest request) {
        return switch (request.kind()) {
            case PRIORITY -> priority(request.options());
            case ATTACK -> attack(request.options());
            case BLOCK -> block(request.options());
        };
    }

    private AgentChoice priority(List<GameAction> options) {
        GameAction land = options.stream().filter(a -> a.type() == GameAction.Type.PLAY_LAND).findFirst().orElse(null);
        if (land != null) {
            return AgentChoice.of(land.id(), "stub: play a land");
        }
        GameAction best = options.stream()
                .filter(a -> a.type() == GameAction.Type.CAST_SPELL)
                .filter(a -> a.targetKind() != GameAction.TargetKind.OWN)
                .max(Comparator.comparingInt(GameAction::weight))
                .orElse(null);
        if (best != null) {
            return AgentChoice.of(best.id(), "stub: cast the most expensive legal spell");
        }
        return AgentChoice.pass("stub: nothing worth doing");
    }

    private AgentChoice attack(List<GameAction> options) {
        Map<Card, AttackAction> chosen = new LinkedHashMap<>();
        for (GameAction option : options) {
            AttackAction atk = (AttackAction) option;
            Card attacker = atk.attacker();
            if (attacker.getNetPower() <= 0 || chosen.containsKey(attacker)) {
                continue;
            }
            if (atk.defender() instanceof Player && safeToAttack(attacker)) {
                chosen.put(attacker, atk);
            }
        }
        List<Integer> ids = chosen.values().stream().map(GameAction::id).collect(Collectors.toList());
        return AgentChoice.of(ids, ids.isEmpty() ? "stub: no safe attacks" : "stub: attack with " + ids.size() + " creature(s)");
    }

    private static boolean safeToAttack(Card attacker) {
        int maxOpposingPower = 0;
        boolean anyBlocker = false;
        for (Player opponent : attacker.getController().getOpponents()) {
            for (Card c : opponent.getCreaturesInPlay()) {
                if (!c.isTapped()) {
                    anyBlocker = true;
                    maxOpposingPower = Math.max(maxOpposingPower, c.getNetPower());
                }
            }
        }
        return !anyBlocker || attacker.getNetToughness() > maxOpposingPower;
    }

    private AgentChoice block(List<GameAction> options) {
        List<BlockAction> blocks = options.stream().map(o -> (BlockAction) o).collect(Collectors.toList());
        if (blocks.isEmpty()) {
            return AgentChoice.none("stub: no blocks possible");
        }
        Player me = blocks.get(0).blocker().getController();
        int incoming = blocks.stream().map(BlockAction::attacker).distinct().mapToInt(Card::getNetPower).sum();
        boolean desperate = me.getLife() <= incoming;

        List<Card> usedBlockers = new ArrayList<>();
        List<Card> blockedAttackers = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (BlockAction b : blocks) {
            Card blocker = b.blocker();
            Card attacker = b.attacker();
            if (usedBlockers.contains(blocker) || blockedAttackers.contains(attacker)) {
                continue;
            }
            boolean survives = blocker.getNetToughness() > attacker.getNetPower();
            boolean kills = blocker.getNetPower() >= attacker.getNetToughness();
            if (survives || kills || desperate) {
                usedBlockers.add(blocker);
                blockedAttackers.add(attacker);
                ids.add(b.id());
            }
        }
        return AgentChoice.of(ids, ids.isEmpty() ? "stub: no profitable blocks" : "stub: " + ids.size() + " block(s)");
    }

    /** Crude "compression": keep the tail of the transcript within a size budget. */
    @Override
    public String summarize(SummaryRequest request) {
        String t = request.transcript() == null ? "" : request.transcript().trim();
        int budget = 700;
        String body = t.length() <= budget ? t : "..." + t.substring(t.length() - budget);
        return "[stub summary of " + request.kind() + " for " + request.playerName() + "]\n" + body;
    }
}
