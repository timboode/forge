package forge.llm.action;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.tinylog.Logger;

import forge.game.Game;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.llm.bridge.AiBridge;
import forge.llm.state.ManaSummary;

/**
 * Hard logic for a priority window: works out which lands/spells/abilities the player can actually put
 * forward right now, and - for things that look usable but are not - why not.
 *
 * Legality is the engine's (timing, zone, restrictions, can the costs be paid, does a legal target
 * exist), not the built-in AI's opinion of whether the play is any good.
 */
public final class PriorityActionEnumerator {
    /** Beyond this many legal targets for one ability we stop listing per-target variants. */
    static final int MAX_TARGET_VARIANTS = 16;
    static final int MAX_UNAVAILABLE_LINES = 30;
    private static final int MAX_DESCRIPTION = 180;

    /**
     * @param excludedKeys actions that already failed to execute in this window; never offered again
     */
    public ActionSet enumerate(Player player, Set<String> excludedKeys) {
        final List<GameAction> actions = new ArrayList<>();
        final Map<String, String> unavailable = new LinkedHashMap<>();

        for (SpellAbility sa : AiBridge.candidateAbilities(player)) {
            try {
                consider(player, sa, excludedKeys, actions, unavailable);
            } catch (RuntimeException e) {
                // one exotic card must never take the whole decision down
                Logger.warn(e, "LLM action enumeration skipped {}", sa);
            }
        }

        final List<UnavailableAction> unavailableList = new ArrayList<>();
        for (Map.Entry<String, String> e : unavailable.entrySet()) {
            if (unavailableList.size() >= MAX_UNAVAILABLE_LINES) {
                break;
            }
            unavailableList.add(new UnavailableAction(e.getKey(), e.getValue()));
        }
        return ActionSet.withPass(new PassAction(passDescription(player.getGame())), actions, unavailableList);
    }

    private static String passDescription(Game game) {
        if (game.getStack().isEmpty()) {
            return "Pass priority (with an empty stack the game moves on to the next step/phase)";
        }
        return "Pass priority (let the top item of the stack resolve; you get priority again afterwards)";
    }

    private void consider(Player player, SpellAbility sa, Set<String> excludedKeys, List<GameAction> actions,
                          Map<String, String> unavailable) {
        if (sa.isManaAbility()) {
            return; // mana is paid automatically while paying costs
        }
        sa.setActivatingPlayer(player);
        AiBridge.resetChoices(sa);
        final Card host = sa.getHostCard();

        final String reason = unplayableReason(player, sa);
        if (reason != null) {
            if (worthMentioning(player, sa)) {
                unavailable.putIfAbsent(subject(sa), reason);
            }
            return;
        }

        final GameAction.Type type = sa.isLandAbility() ? GameAction.Type.PLAY_LAND
                : sa.isSpell() ? GameAction.Type.CAST_SPELL : GameAction.Type.ACTIVATE_ABILITY;
        final int weight = sa.isSpell() ? host.getCMC() : 0;

        final List<GameObject> targets = singleTargetCandidates(player, sa);
        if (targets == null) {
            add(actions, excludedKeys, build(type, sa, null, weight));
        } else {
            for (GameObject target : targets) {
                add(actions, excludedKeys, build(type, sa, target, weight));
            }
        }
    }

    private static void add(List<GameAction> actions, Set<String> excludedKeys, PriorityAction action) {
        if (!excludedKeys.contains(action.key())) {
            actions.add(action);
        }
    }

    // ---- legality ----------------------------------------------------------------------------------------

    /** @return null if the ability can be put forward right now, otherwise a short human-readable reason */
    static String unplayableReason(Player player, SpellAbility sa) {
        if (!AiBridge.canPlayNow(sa)) {
            return explainTiming(player, sa);
        }
        if (sa.isLandAbility()) {
            return null;
        }
        // targets: every targeting ability in the chain needs at least one legal target
        for (SpellAbility part = sa; part != null; part = part.getSubAbility()) {
            if (part.usesTargeting() && part.getMinTargets() > 0 && !AiBridge.hasAnyTarget(part)) {
                return "no legal target";
            }
        }
        final Boolean mana = AiBridge.withCastContext(sa, () -> AiBridge.canPayMana(sa, player));
        if (mana == null || !mana) {
            return "cannot pay the mana cost " + sa.getPayCosts().getTotalMana() + " (" + ManaSummary.describe(player) + ")";
        }
        final Boolean other = AiBridge.withCastContext(sa, () -> AiBridge.canPayAdditionalCosts(sa, player));
        if (other == null || !other) {
            return "cannot pay the costs (" + sa.getPayCosts().toSimpleString() + ")";
        }
        return null;
    }

    private static String explainTiming(Player player, SpellAbility sa) {
        final Game game = player.getGame();
        final Card host = sa.getHostCard();
        final PhaseHandler ph = game.getPhaseHandler();

        if (sa.isLandAbility()) {
            return "cannot play a land now (land drop already used, stack not empty, or not a main phase of your own turn)";
        }
        if (game.getStack().isSplitSecondOnStack()) {
            return "a spell with split second is on the stack";
        }
        final boolean instantSpeed = sa.isSpell()
                ? (host.isInstant() || host.hasKeyword(Keyword.FLASH))
                : !sa.getRestrictions().isSorcerySpeed();
        if (!instantSpeed) {
            if (!ph.isPlayerTurn(player)) {
                return "sorcery-speed: only usable during your own turn";
            }
            if (!ph.getPhase().isMain()) {
                return "sorcery-speed: only usable during your own main phase";
            }
            if (!game.getStack().isEmpty()) {
                return "sorcery-speed: only usable with an empty stack";
            }
        }
        if (sa.isActivatedAbility() && host.isInPlay() && sa.getPayCosts() != null && sa.getPayCosts().hasTapCost()) {
            if (host.isTapped()) {
                return "its {T} cost cannot be paid: it is tapped";
            }
            if (host.isCreature() && host.isSick()) {
                return "its {T} cost cannot be paid: summoning sick";
            }
        }
        return "not usable right now (timing, zone or a card-specific restriction)";
    }

    /** Keep the "cannot do" list to things the player would plausibly wonder about. */
    private static boolean worthMentioning(Player player, SpellAbility sa) {
        final Card host = sa.getHostCard();
        if (host == null || sa.isManaAbility()) {
            return false;
        }
        if (host.getZone() == null) {
            return false;
        }
        final ZoneType zone = host.getZone().getZoneType();
        if (zone == ZoneType.Hand) {
            return host.getController().equals(player);
        }
        return zone == ZoneType.Battlefield && host.getController().equals(player) && sa.isActivatedAbility();
    }

    // ---- targets -----------------------------------------------------------------------------------------

    /**
     * @return the legal single targets if the ability targets exactly one thing and the number of candidates
     *         is manageable (each becomes its own action); null if the ability does not target, targets
     *         several things, or has too many candidates - targeting is then left to the AI helper.
     */
    private static List<GameObject> singleTargetCandidates(Player player, SpellAbility sa) {
        if (!sa.usesTargeting() || sa.getMaxTargets() != 1 || sa.getMinTargets() > 1) {
            return null;
        }
        final List<GameObject> out = AiBridge.targetCandidates(sa);
        if (out.isEmpty() || out.size() > MAX_TARGET_VARIANTS) {
            return null;
        }
        return out;
    }

    // ---- description -------------------------------------------------------------------------------------

    private static PriorityAction build(GameAction.Type type, SpellAbility sa, GameObject target, int weight) {
        final Card host = sa.getHostCard();
        final StringBuilder d = new StringBuilder();
        switch (type) {
            case PLAY_LAND -> d.append("Play land: ").append(cardLabel(host));
            case CAST_SPELL -> d.append("Cast ").append(cardLabel(host));
            default -> d.append("Activate ").append(cardLabel(host)).append(" ability");
        }
        if (type != GameAction.Type.PLAY_LAND) {
            final String cost = sa.getPayCosts() == null ? "" : sa.getPayCosts().toSimpleString();
            if (!cost.isEmpty()) {
                d.append(" [cost: ").append(cost).append("]");
            }
            final String text = abbreviate(sa.getDescription());
            if (!text.isEmpty()) {
                d.append(" - ").append(text);
            }
        }
        String targetLabel = null;
        GameAction.TargetKind kind = GameAction.TargetKind.NONE;
        if (target != null) {
            targetLabel = targetLabel(sa.getActivatingPlayer(), target);
            kind = targetKind(sa.getActivatingPlayer(), target);
            d.append(" -> TARGET: ").append(targetLabel);
        } else if (sa.usesTargeting()) {
            d.append(" (targets are chosen automatically)");
        }
        final String key = host.getId() + "|" + sa.getDescription() + "|" + sa.getPayCosts() + "|" + (target == null ? "-" : targetKey(target));
        return new PriorityAction(type, sa, target, targetLabel, kind, d.toString(), weight, key);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        s = s.replace('\n', ' ').trim();
        return s.length() <= MAX_DESCRIPTION ? s : s.substring(0, MAX_DESCRIPTION - 3) + "...";
    }

    public static String cardLabel(Card c) {
        return c.getName() + " (#" + c.getId() + ")";
    }

    private static String subject(SpellAbility sa) {
        final Card host = sa.getHostCard();
        if (sa.isSpell() || sa.isLandAbility()) {
            return cardLabel(host);
        }
        return cardLabel(host) + " ability: " + abbreviate(sa.getDescription());
    }

    private static String targetLabel(Player viewer, GameObject target) {
        if (target instanceof Card c) {
            if (!c.getView().canBeShownTo(viewer.getView())) {
                return "a hidden card";
            }
            return cardLabel(c) + (c.getController() == null ? "" : " controlled by " + c.getController().getName());
        }
        if (target instanceof Player p) {
            return "player " + p.getName();
        }
        if (target instanceof SpellAbility s) {
            return "the " + (s.isSpell() ? "spell " : "ability ") + cardLabel(s.getHostCard());
        }
        return target.toString();
    }

    private static String targetKey(GameObject target) {
        if (target instanceof Card c) {
            return "c" + c.getId();
        }
        if (target instanceof Player p) {
            return "p" + p.getId();
        }
        if (target instanceof SpellAbility s) {
            return "s" + s.getId();
        }
        return target.toString();
    }

    private static GameAction.TargetKind targetKind(Player me, GameObject target) {
        Player owner = null;
        if (target instanceof Card c) {
            owner = c.getController();
        } else if (target instanceof Player p) {
            owner = p;
        } else if (target instanceof SpellAbility s) {
            owner = s.getActivatingPlayer();
        }
        if (owner == null) {
            return GameAction.TargetKind.NEUTRAL;
        }
        return owner.equals(me) ? GameAction.TargetKind.OWN : GameAction.TargetKind.OPPONENT;
    }
}
