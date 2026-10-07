package forge.llm.state;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

/**
 * Turns the live game into text from one player's point of view. Only information that player is entitled
 * to see is rendered: their own hand, but just the size of every other hand and of every library.
 */
public final class GameStateRenderer {

    /** The board text plus the distinct cards it mentions (for the oracle-text reference section). */
    public record Rendered(String board, List<Card> mentioned) {
    }

    public Rendered render(Player me) {
        final Game game = me.getGame();
        final PhaseHandler ph = game.getPhaseHandler();
        final Map<String, Card> mentioned = new LinkedHashMap<>();
        final StringBuilder sb = new StringBuilder();

        sb.append("Turn ").append(ph.getTurn()).append(" - ").append(ph.getPlayerTurn().getName()).append("'s turn - ")
                .append(ph.getPhase().nameForUi).append(" - priority: ").append(ph.getPriorityPlayer().getName()).append('\n');
        if (ph.isPlayerTurn(me)) {
            sb.append("It is YOUR turn.\n");
        } else {
            sb.append("It is an OPPONENT's turn - you can only act at instant speed.\n");
        }
        sb.append("Your available mana: ").append(ManaSummary.describe(me)).append('\n');
        sb.append("Lands played this turn: ").append(me.getLandsPlayedThisTurn()).append(" of ")
                .append(me.getMaxLandPlaysInfinite() ? "unlimited" : String.valueOf(me.getMaxLandPlays())).append('\n');

        sb.append("\n## Players\n");
        playerBlock(sb, me, me, mentioned);
        for (Player p : game.getPlayers()) {
            if (!p.equals(me)) {
                playerBlock(sb, me, p, mentioned);
            }
        }

        sb.append("\n## Your hand\n");
        CardCollectionView hand = me.getCardsIn(ZoneType.Hand);
        if (hand.isEmpty()) {
            sb.append("(empty)\n");
        }
        for (Card c : hand) {
            mention(mentioned, c);
            sb.append("- ").append(c.getName()).append(" (#").append(c.getId()).append(") ").append(costOf(c))
                    .append(c.getType()).append('\n');
        }

        sb.append("\n## Stack (top first)\n");
        if (game.getStack().isEmpty()) {
            sb.append("(empty)\n");
        } else {
            int i = 1;
            for (SpellAbilityStackInstance si : game.getStack()) {
                Card host = si.getSourceCard();
                if (host != null) {
                    mention(mentioned, host);
                }
                sb.append(i++).append(". ").append(si.getStackDescription()).append(" [controlled by ")
                        .append(si.getSpellAbility().getActivatingPlayer() == null ? "?" : si.getSpellAbility().getActivatingPlayer().getName()).append("]\n");
            }
        }

        final Combat combat = ph.getCombat();
        if (combat != null && !combat.getAttackers().isEmpty()) {
            sb.append("\n## Combat\n");
            for (Card attacker : combat.getAttackers()) {
                sb.append("- ").append(attacker.getName()).append(" (#").append(attacker.getId()).append(") ")
                        .append(pt(attacker)).append(" attacks ").append(combat.getDefenderByAttacker(attacker));
                CardCollection blockers = combat.getBlockers(attacker);
                if (blockers != null && !blockers.isEmpty()) {
                    sb.append(", blocked by ");
                    List<String> names = new ArrayList<>();
                    for (Card b : blockers) {
                        names.add(b.getName() + " (#" + b.getId() + ") " + pt(b));
                    }
                    sb.append(String.join(", ", names));
                } else if (ph.getPhase().isAfter(forge.game.phase.PhaseType.COMBAT_DECLARE_BLOCKERS)) {
                    sb.append(", unblocked");
                }
                sb.append('\n');
            }
        }
        return new Rendered(sb.toString().stripTrailing(), new ArrayList<>(mentioned.values()));
    }

    /** Oracle text for the given cards, skipping names already sent earlier in the session. */
    public String cardReference(List<Card> cards, java.util.Set<String> alreadySent) {
        final StringBuilder sb = new StringBuilder();
        for (Card c : cards) {
            if (!alreadySent.add(c.getName())) {
                continue;
            }
            String text = c.getOracleText();
            sb.append("- ").append(c.getName()).append(": ").append(costOf(c)).append(c.getType());
            if (c.isCreature()) {
                sb.append(' ').append(c.getCurrentPower()).append('/').append(c.getCurrentToughness());
            }
            if (text != null && !text.isBlank()) {
                sb.append(" | ").append(text.replace("\r", "").replace("\\n", "\n").replace("\n", " / "));
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    // ---- players and zones ------------------------------------------------------------------------------

    private void playerBlock(StringBuilder sb, Player me, Player p, Map<String, Card> mentioned) {
        sb.append("\n### ").append(p.equals(me) ? "YOU" : "OPPONENT").append(": ").append(p.getName())
                .append(" | life ").append(p.getLife());
        if (p.getPoisonCounters() > 0) {
            sb.append(" | poison ").append(p.getPoisonCounters());
        }
        sb.append(" | hand ").append(p.getCardsIn(ZoneType.Hand).size())
                .append(" | library ").append(p.getCardsIn(ZoneType.Library).size())
                .append(" | graveyard ").append(p.getCardsIn(ZoneType.Graveyard).size())
                .append(" | exile ").append(p.getCardsIn(ZoneType.Exile).size()).append('\n');

        CardCollectionView battlefield = p.getCardsIn(ZoneType.Battlefield);
        CardCollectionView creatures = CardLists.filter(battlefield, Card::isCreature);
        CardCollectionView lands = CardLists.filter(battlefield, c -> c.isLand() && !c.isCreature());
        CardCollectionView others = CardLists.filter(battlefield, c -> !c.isCreature() && !c.isLand());

        sb.append("Battlefield:\n");
        if (battlefield.isEmpty()) {
            sb.append("  (nothing)\n");
        }
        permanents(sb, "Creatures", creatures, me, mentioned);
        permanents(sb, "Other permanents", others, me, mentioned);
        landLine(sb, lands, mentioned);

        zoneLine(sb, "Graveyard", p.getCardsIn(ZoneType.Graveyard), me, mentioned);
        zoneLine(sb, "Exile", p.getCardsIn(ZoneType.Exile), me, mentioned);
        // the engine parks its own bookkeeping effects in the command zone too; only real cards and emblems matter
        zoneLine(sb, "Command zone", CardLists.filter(p.getCardsIn(ZoneType.Command), c -> !c.isImmutable() || c.isEmblem()), me, mentioned);
        if (CommanderSummary.isCommanderGame(p.getGame())) {
            for (String line : CommanderSummary.linesFor(p)) {
                sb.append(line).append('\n');
            }
        }
    }

    private void permanents(StringBuilder sb, String label, CardCollectionView cards, Player me, Map<String, Card> mentioned) {
        if (cards.isEmpty()) {
            return;
        }
        sb.append("  ").append(label).append(":\n");
        for (Card c : cards) {
            if (!c.getView().canBeShownTo(me.getView())) {
                // no id either: ids are handed out in decklist order, so one next to known cards would hint at identity
                sb.append("    - a face-down permanent controlled by ").append(c.getController().getName()).append('\n');
                continue;
            }
            mention(mentioned, c);
            sb.append("    - ").append(c.getName()).append(" (#").append(c.getId()).append(")");
            if (c.isCreature()) {
                sb.append(' ').append(pt(c));
                if (c.getDamage() > 0) {
                    sb.append(" (").append(c.getDamage()).append(" damage)");
                }
            }
            sb.append(" [").append(c.getType()).append(']');
            sb.append(c.isTapped() ? " TAPPED" : " untapped");
            if (c.isCreature() && c.isSick() && c.getController().equals(c.getGame().getPhaseHandler().getPlayerTurn())) {
                sb.append(", summoning sick");
            }
            if (c.isToken()) {
                sb.append(", token");
            }
            String counters = counters(c);
            if (!counters.isEmpty()) {
                sb.append(", counters: ").append(counters);
            }
            if (c.getAttachedTo() != null) {
                sb.append(", attached to ").append(c.getAttachedTo());
            }
            if (c.getAttachedCards() != null && !c.getAttachedCards().isEmpty()) {
                List<String> names = new ArrayList<>();
                for (Card a : c.getAttachedCards()) {
                    names.add(a.getName() + " (#" + a.getId() + ")");
                }
                sb.append(", attached: ").append(String.join(", ", names));
            }
            sb.append('\n');
        }
    }

    private void landLine(StringBuilder sb, CardCollectionView lands, Map<String, Card> mentioned) {
        if (lands.isEmpty()) {
            return;
        }
        Map<String, int[]> grouped = new LinkedHashMap<>(); // name -> {total, untapped}
        for (Card c : lands) {
            mention(mentioned, c);
            int[] n = grouped.computeIfAbsent(c.getName(), k -> new int[2]);
            n[0]++;
            if (!c.isTapped()) {
                n[1]++;
            }
        }
        List<String> parts = new ArrayList<>();
        grouped.forEach((name, n) -> parts.add(name + " x" + n[0] + " (" + n[1] + " untapped)"));
        sb.append("  Lands: ").append(String.join(", ", parts)).append('\n');
    }

    private void zoneLine(StringBuilder sb, String label, CardCollectionView cards, Player me, Map<String, Card> mentioned) {
        if (cards.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (Card c : cards) {
            if (!c.getView().canBeShownTo(me.getView())) {
                names.add("a face-down card");
                continue;
            }
            mention(mentioned, c);
            names.add(c.getName() + " (#" + c.getId() + ")");
        }
        sb.append(label).append(": ").append(String.join(", ", names)).append('\n');
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private static void mention(Map<String, Card> mentioned, Card c) {
        mentioned.putIfAbsent(c.getName(), c);
    }

    private static String pt(Card c) {
        return c.getNetPower() + "/" + c.getNetToughness();
    }

    private static String costOf(Card c) {
        return c.getManaCost() == null || c.getManaCost().isNoCost() ? "" : c.getManaCost() + " ";
    }

    private static String counters(Card c) {
        StringBuilder sb = new StringBuilder();
        for (com.google.common.collect.Multiset.Entry<CounterType> e : c.getCounters().entrySet()) {
            if (e.getCount() > 0) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(e.getElement().getCounterOnCardDisplayName()).append(" x").append(e.getCount());
            }
        }
        return sb.toString();
    }
}
