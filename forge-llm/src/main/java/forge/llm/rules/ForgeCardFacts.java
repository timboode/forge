package forge.llm.rules;

import forge.StaticData;
import forge.card.CardRules;
import forge.item.PaperCard;

/**
 * Card lookups for {@link RulesLibrary} from the game's own card database: a phrase that is exactly a card
 * name returns that card's mana cost, type line and rules text. Forge's card data has no official rulings,
 * so the card's own text is what a lookup can return.
 */
public final class ForgeCardFacts {
    private ForgeCardFacts() {
    }

    /** The facts block for an exact card name, or null when the phrase is not a card name. */
    public static String facts(String phrase) {
        final String name = phrase.trim();
        if (name.isEmpty()) {
            return null;
        }
        try {
            final PaperCard card = StaticData.instance().fetchCard(name);
            if (card == null || !card.getName().equalsIgnoreCase(name)) {
                return null;
            }
            final CardRules rules = card.getRules();
            final StringBuilder sb = new StringBuilder("CARD: ").append(card.getName());
            if (rules.getManaCost() != null && !rules.getManaCost().isNoCost()) {
                sb.append(' ').append(rules.getManaCost());
            }
            sb.append(" - ").append(rules.getType());
            final String text = rules.getOracleText();
            if (text != null && !text.isBlank()) {
                sb.append(" - ").append(text.replace("\n", " / "));
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return null; // no initialised game context to ask: no card facts
        }
    }
}
