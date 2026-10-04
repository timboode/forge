package forge.llm.action;

import forge.game.card.Card;

/** Block one attacking creature with one of the player's creatures. */
public final class BlockAction extends GameAction {
    private final Card blocker;
    private final Card attacker;

    BlockAction(Card blocker, Card attacker) {
        this.blocker = blocker;
        this.attacker = attacker;
    }

    @Override
    public Type type() {
        return Type.BLOCK;
    }

    public Card blocker() {
        return blocker;
    }

    public Card attacker() {
        return attacker;
    }

    @Override
    public String describe() {
        return "Block " + PriorityActionEnumerator.cardLabel(attacker) + " " + attacker.getNetPower() + "/"
                + attacker.getNetToughness() + " with " + PriorityActionEnumerator.cardLabel(blocker) + " "
                + blocker.getNetPower() + "/" + blocker.getNetToughness();
    }

    @Override
    public String key() {
        return "blk|" + blocker.getId() + "|" + attacker.getId();
    }
}
