package forge.llm.action;

import java.util.ArrayList;
import java.util.List;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.player.Player;

/** Which attacks and blocks are legal for a player, straight from the engine's own combat rules. */
public final class CombatActionEnumerator {

    /** Every (creature, defender) pair that could legally be declared as an attack, and why the rest cannot attack. */
    public ActionSet attacks(Player attacker, Combat combat) {
        final List<GameAction> out = new ArrayList<>();
        final List<UnavailableAction> unavailable = new ArrayList<>();
        for (Card creature : attacker.getCreaturesInPlay()) {
            if (!CombatUtil.canAttack(creature)) {
                unavailable.add(new UnavailableAction(PriorityActionEnumerator.cardLabel(creature) + " cannot attack",
                        whyNotAttacking(creature)));
                continue;
            }
            for (GameEntity defender : combat.getDefenders()) {
                if (CombatUtil.canAttack(creature, defender)) {
                    out.add(new AttackAction(creature, defender));
                }
            }
        }
        return new ActionSet(out, unavailable);
    }

    /** Every (blocker, attacker) pair that could legally be declared as a block by {@code defender}, and why the rest cannot block. */
    public ActionSet blocks(Player defender, Combat combat) {
        final List<GameAction> out = new ArrayList<>();
        final List<UnavailableAction> unavailable = new ArrayList<>();
        final List<Card> attackers = new ArrayList<>();
        for (Card a : combat.getAttackers()) {
            if (defender.equals(combat.getDefenderPlayerByAttacker(a))) {
                attackers.add(a);
            }
        }
        for (Card blocker : defender.getCreaturesInPlay()) {
            if (!CombatUtil.canBlock(blocker, combat)) {
                unavailable.add(new UnavailableAction(PriorityActionEnumerator.cardLabel(blocker) + " cannot block",
                        blocker.isTapped() ? "it is tapped" : "a restriction or effect prevents it from blocking"));
                continue;
            }
            boolean blocksAny = false;
            for (Card attacker : attackers) {
                if (CombatUtil.canBlock(attacker, blocker, combat)) {
                    out.add(new BlockAction(blocker, attacker));
                    blocksAny = true;
                }
            }
            if (!blocksAny) {
                unavailable.add(new UnavailableAction(PriorityActionEnumerator.cardLabel(blocker) + " cannot block",
                        "none of the attackers can be blocked by it (evasion such as flying/fear, or a restriction)"));
            }
        }
        return new ActionSet(out, unavailable);
    }

    private static String whyNotAttacking(Card creature) {
        if (creature.isTapped()) {
            return "it is tapped";
        }
        if (creature.isSick()) {
            return "summoning sick (it has not been under your control since your turn began)";
        }
        if (creature.hasKeyword(Keyword.DEFENDER)) {
            return "it has defender";
        }
        return "a restriction or effect prevents it from attacking";
    }
}
