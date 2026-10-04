package forge.llm.action;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.player.Player;

/** Attack a specific player/planeswalker/battle with one creature. */
public final class AttackAction extends GameAction {
    private final Card attacker;
    private final GameEntity defender;

    AttackAction(Card attacker, GameEntity defender) {
        this.attacker = attacker;
        this.defender = defender;
    }

    @Override
    public Type type() {
        return Type.ATTACK;
    }

    public Card attacker() {
        return attacker;
    }

    public GameEntity defender() {
        return defender;
    }

    @Override
    public String describe() {
        return "Attack with " + PriorityActionEnumerator.cardLabel(attacker) + " " + attacker.getNetPower() + "/"
                + attacker.getNetToughness() + " -> " + defenderLabel(defender);
    }

    @Override
    public String key() {
        return "atk|" + attacker.getId() + "|" + defender.getId();
    }

    static String defenderLabel(GameEntity defender) {
        if (defender instanceof Player p) {
            return "player " + p.getName();
        }
        if (defender instanceof Card c) {
            return PriorityActionEnumerator.cardLabel(c) + " controlled by " + c.getController().getName();
        }
        return defender.toString();
    }
}
