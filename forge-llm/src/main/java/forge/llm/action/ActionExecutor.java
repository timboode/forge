package forge.llm.action;

import java.util.List;

import forge.ai.AiController;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.llm.bridge.AiBridge;

/**
 * Turns a chosen {@link PriorityAction} into something the engine's priority loop can play.
 *
 * The engine plays the returned ability through the normal AI path (pay costs automatically, put it on the
 * stack). Before that, the ability needs its X value, modes and targets filled in; the built-in AI helper
 * does that, and an explicit target chosen by the agent then overrides whatever the helper picked.
 */
public final class ActionExecutor {
    private final Player player;
    private final AiController ai;

    public ActionExecutor(Player player, AiController ai) {
        this.player = player;
        this.ai = ai;
    }

    /** @return the abilities to hand to the engine, or null if the action could not be set up */
    public List<SpellAbility> prepare(PriorityAction action) {
        final SpellAbility sa = action.spellAbility();
        sa.setActivatingPlayer(player);
        AiBridge.resetChoices(sa); // targets/X may be left over from an earlier evaluation of this long-lived object
        if (sa.isLandAbility()) {
            return List.of(sa);
        }

        if (!AiBridge.prepareWithAi(ai, sa).completed()) {
            return null; // timed out or failed: the ability may be half set up, so it must not be played
        }
        if (action.target() != null) {
            sa.resetTargets(); // some targeting rules depend on targets already chosen, so judge the new one on a clean slate
            if (!AiBridge.canTarget(sa, action.target())) {
                return null; // the situation changed since the options were listed
            }
            sa.getTargets().add(action.target());
        }
        for (SpellAbility part = sa; part != null; part = part.getSubAbility()) {
            if (part.usesTargeting() && !part.isTargetNumberValid()) {
                return null;
            }
        }
        return List.of(sa);
    }
}
