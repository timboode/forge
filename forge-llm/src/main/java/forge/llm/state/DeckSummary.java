package forge.llm.state;

import java.util.Map;
import java.util.TreeMap;

import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;

/** Decklist text, and the deduced composition of the (unordered) library. */
public final class DeckSummary {
    private DeckSummary() {
    }

    /** name -> copies, for one deck section, sorted by name. */
    private static Map<String, Integer> counts(Deck deck, DeckSection section) {
        Map<String, Integer> counts = new TreeMap<>();
        if (deck != null && deck.has(section)) {
            for (Map.Entry<PaperCard, Integer> e : deck.get(section)) {
                counts.merge(e.getKey().getName(), e.getValue(), Integer::sum);
            }
        }
        return counts;
    }

    public static String decklist(Player player) {
        Deck deck = player.getRegisteredPlayer() == null ? null : player.getRegisteredPlayer().getDeck();
        StringBuilder sb = new StringBuilder();
        Map<String, Integer> main = counts(deck, DeckSection.Main);
        int total = main.values().stream().mapToInt(Integer::intValue).sum();
        sb.append("Main deck (").append(total).append(" cards):\n");
        main.forEach((name, n) -> sb.append("  ").append(n).append(' ').append(name).append('\n'));
        Map<String, Integer> commanders = counts(deck, DeckSection.Commander);
        if (!commanders.isEmpty()) {
            sb.append("Commander:\n");
            commanders.forEach((name, n) -> sb.append("  ").append(n).append(' ').append(name).append('\n'));
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Decklist minus every card of yours the player has already seen (any zone but the library). What is
     * left is exactly the multiset of cards in the library - order stays unknown, as it should.
     */
    public static String libraryComposition(Player player) {
        Deck deck = player.getRegisteredPlayer() == null ? null : player.getRegisteredPlayer().getDeck();
        Map<String, Integer> remaining = counts(deck, DeckSection.Main);
        for (Card c : player.getGame().getCardsInGame()) {
            if (!c.getOwner().equals(player) || c.isToken() || c.getPaperCard() == null) {
                continue;
            }
            if (c.isInZone(ZoneType.Library) || c.isInZone(ZoneType.Command)) {
                continue;
            }
            if (!c.getView().canBeShownTo(player.getView())) {
                continue; // e.g. a card of yours exiled face down by an opponent: you must not learn what it was
            }
            remaining.computeIfPresent(c.getPaperCard().getName(), (k, v) -> v > 1 ? v - 1 : null);
        }
        StringBuilder sb = new StringBuilder();
        int total = remaining.values().stream().mapToInt(Integer::intValue).sum();
        sb.append(total).append(" cards (unordered):\n");
        remaining.forEach((name, n) -> sb.append("  ").append(n).append(' ').append(name).append('\n'));
        return sb.toString().stripTrailing();
    }
}
