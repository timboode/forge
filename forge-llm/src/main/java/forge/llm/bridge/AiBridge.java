package forge.llm.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.tinylog.Logger;

import forge.ai.AiCache;
import forge.ai.AiCardMemory;
import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilMana;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.cost.CostPayment;
import forge.game.player.Player;
import forge.game.spellability.Spell;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;

/**
 * The single place where this module reaches into forge-ai / engine helpers that are not a clean public
 * API (they are public, but written for the built-in AI), or that have quirks we compensate for.
 * Everything the rest of the module needs from them goes through here, so that an upstream refactor of
 * those helpers breaks exactly one file.
 */
public final class AiBridge {
    private AiBridge() {
    }

    // ---- what could be played --------------------------------------------------------------------------

    /**
     * Every ability/spell the player could conceivably put forward at priority (hand, battlefield,
     * graveyard flashback, exile permissions...), with alternative-cost variants expanded and identical
     * cards (e.g. four Forests in hand) deduplicated. Not yet filtered for legality.
     */
    public static List<SpellAbility> candidateAbilities(Player player) {
        CardCollection cards = ComputerUtilCard.dedupeCards(ComputerUtilAbility.getAvailableCards(player.getGame(), player));
        List<SpellAbility> all = ComputerUtilAbility.getSpellAbilities(cards, player);
        return ComputerUtilAbility.getOriginalAndAltCostAbilities(all, player);
    }

    /**
     * Runs {@code body} with the spell in the same "being considered for casting" state the AI uses
     * (alternate host for the cast, cast-SA pointer set), then restores it. Cost adjustments and target
     * checks of some cards depend on this state.
     *
     * @return the body's result, or null if the spell cannot be cast from where it is
     */
    public static <T> T withCastContext(SpellAbility sa, Supplier<T> body) {
        final Card host = sa.getHostCard();
        if (sa instanceof Spell sp) {
            Card altHost = sp.canPlayFromHost();
            if (altHost == null) {
                return null;
            }
            if (host != altHost) {
                sa.setHostCard(altHost);
            }
            altHost.setCastSA(sa);
        }
        try {
            return body.get();
        } finally {
            if (sa.getHostCard() != host) {
                sa.setHostCard(host);
            }
            if (sa.isSpell()) {
                host.setCastSA(null);
            }
        }
    }

    /** Pure engine legality of timing + zone + restrictions (no AI opinion). */
    public static boolean canPlayNow(SpellAbility sa) {
        if (sa instanceof Spell sp) {
            return sp.canPlayFromHost() != null;
        }
        return sa.canPlay();
    }

    public static boolean canPayMana(SpellAbility sa, Player player) {
        return ComputerUtilMana.canPayManaCost(sa, player, 0, false);
    }

    public static boolean canPayAdditionalCosts(SpellAbility sa, Player player) {
        return CostPayment.canPayAdditionalCosts(sa.getPayCosts(), sa, false, player);
    }

    // ---- targets ---------------------------------------------------------------------------------------

    /**
     * Clears targets and a leftover X on every ability in the chain. Abilities are long-lived objects that
     * earlier AI evaluations (ours or anybody's) may have left targets and X values on, which would hide
     * legal candidates and corrupt the next play.
     */
    public static void resetChoices(SpellAbility sa) {
        for (SpellAbility part = sa; part != null; part = part.getSubAbility()) {
            part.resetTargets();
        }
        sa.getRootAbility().setXManaCostPaid(null);
        sa.setSkip(false); // set by a failed earlier attempt; AI helper code bails out on skipped abilities
    }

    /** Does a targeting ability have at least one legal target right now? */
    public static boolean hasAnyTarget(SpellAbility sa) {
        final TargetRestrictions tr = sa.getTargetRestrictions();
        if (!targetsStack(tr)) {
            return tr.hasCandidates(sa);
        }
        // hasCandidates() answers "yes" for anything that may target the stack, whether or not the stack
        // holds a legal target, so for those we enumerate ourselves.
        return !targetCandidates(sa).isEmpty();
    }

    /** Every legal single target of a targeting ability, including spells/abilities on the stack. */
    public static List<GameObject> targetCandidates(SpellAbility sa) {
        final TargetRestrictions tr = sa.getTargetRestrictions();
        final List<GameObject> out = new ArrayList<>();
        if (!targetsStack(tr)) {
            out.addAll(tr.getAllCandidates(sa));
            return out;
        }
        final Game game = sa.getActivatingPlayer().getGame();
        for (SpellAbilityStackInstance si : game.getStack()) {
            if (sa.canTargetSpellAbility(si.getSpellAbility())) {
                out.add(si.getSpellAbility());
            }
        }
        out.addAll(tr.getAllCandidates(sa, true)); // players
        for (ZoneType zone : tr.getZone()) {
            if (zone == ZoneType.Stack) {
                continue; // the spells themselves, covered through their stack instances above
            }
            for (Card c : game.getCardsIn(zone)) {
                if (sa.canTarget(c)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private static boolean targetsStack(TargetRestrictions tr) {
        return tr.getZone().contains(ZoneType.Stack);
    }

    public static boolean canTarget(SpellAbility sa, GameObject target) {
        if (target instanceof GameEntity entity) {
            return sa.canTarget(entity);
        }
        if (target instanceof SpellAbility spell) {
            return sa.canTargetSpellAbility(spell);
        }
        return false;
    }

    // ---- borrowing the built-in AI's setup work ---------------------------------------------------------

    /**
     * Start of one decision: the built-in AI wipes its per-decision caches and "keep this mana for my next
     * spell" reservations every time it is asked what to do. We bypass that code path, so do the same here,
     * otherwise stale reservations make mana look unavailable.
     */
    public static void beginDecision(AiController ai, Player player) {
        AiCache.clear();
        clearManaReservations(ai, player);
    }

    private static void clearManaReservations(AiController ai, Player player) {
        AiCardMemory.clearMemorySet(ai, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
        AiCardMemory.clearMemorySet(ai, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2);
        AiCardMemory.clearMemorySet(ai, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_DECLBLK);
        AiCardMemory.clearMemorySet(ai, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK);
    }

    /**
     * Lets the built-in AI do the fiddly setup it already knows how to do for a chosen ability: choose X,
     * choose targets, pick modes. The AI's verdict ("I wouldn't play this") is deliberately ignored - the
     * LLM already decided to play it - only the side effects on the ability are wanted, and the AI's own
     * plans for the mana (reservations) are discarded afterwards.
     *
     * Like the AI itself, the evaluation runs on a helper thread with the game's AI timeout, because some
     * card evaluations can loop for ever on odd board states.
     *
     * @return the outcome; {@link Prepared#completed()} is false if the evaluation timed out or failed, in which
     *         case the ability may be half set up and must not be played
     */
    public static Prepared prepareWithAi(AiController ai, SpellAbility sa) {
        final Player activator = sa.getActivatingPlayer();
        final Game game = activator.getGame();
        final FutureTask<AiPlayDecision> task = new FutureTask<>(() -> withCastContext(sa, () -> {
            if (sa.getRootAbility().isSpell()) {
                sa.setLastStateBattlefield(game.getLastStateBattlefield());
                sa.setLastStateGraveyard(game.getLastStateGraveyard());
            }
            try {
                return ai.canPlaySa(sa);
            } finally {
                sa.clearLastState();
            }
        }));
        final Thread worker = new Thread(task, "LLM player AI setup");
        worker.setDaemon(true);
        worker.start();
        try {
            return new Prepared(task.get(game.getAITimeout(), TimeUnit.SECONDS), true);
        } catch (TimeoutException e) {
            Logger.warn("LLM player: AI setup for {} timed out after {}s", sa, game.getAITimeout());
            abandon(task, worker);
            return new Prepared(null, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abandon(task, worker);
            return new Prepared(null, false);
        } catch (ExecutionException e) {
            Logger.warn(e.getCause(), "LLM player: AI setup for {} failed", sa);
            return new Prepared(null, false);
        } finally {
            clearManaReservations(ai, activator);
        }
    }

    /** Outcome of {@link #prepareWithAi}: the AI's verdict (null if the ability is not castable), and whether the evaluation ran to completion. */
    public record Prepared(AiPlayDecision decision, boolean completed) {
    }

    /** Asks a runaway evaluation to stop, the way the built-in AI does: interrupt, wait briefly, then stop it if it still runs. */
    private static void abandon(FutureTask<?> task, Thread worker) {
        task.cancel(true);
        try {
            worker.join(2000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            try {
                worker.stop();
            } catch (UnsupportedOperationException | NoSuchMethodError ex) {
                // removed in newer Java versions; the daemon thread is left to finish on its own
            }
        }
    }
}
