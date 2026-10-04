package forge.llm.action;

import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

/** Play a land, cast a spell or activate an ability, optionally with an explicit single target. */
public final class PriorityAction extends GameAction {
    private final Type type;
    private final SpellAbility sa;
    private final GameObject target;
    private final String targetLabel;
    private final TargetKind targetKind;
    private final String description;
    private final int weight;
    private final String key;

    PriorityAction(Type type, SpellAbility sa, GameObject target, String targetLabel, TargetKind targetKind,
                   String description, int weight, String key) {
        this.type = type;
        this.sa = sa;
        this.target = target;
        this.targetLabel = targetLabel;
        this.targetKind = targetKind;
        this.description = description;
        this.weight = weight;
        this.key = key;
    }

    @Override
    public Type type() {
        return type;
    }

    @Override
    public String describe() {
        return description;
    }

    @Override
    public int weight() {
        return weight;
    }

    @Override
    public TargetKind targetKind() {
        return targetKind;
    }

    @Override
    public String key() {
        return key;
    }

    public SpellAbility spellAbility() {
        return sa;
    }

    public Card host() {
        return sa.getHostCard();
    }

    /** The explicitly chosen target, or null if targets (if any) are left to the engine/AI helper. */
    public GameObject target() {
        return target;
    }

    public String targetLabel() {
        return targetLabel;
    }
}
