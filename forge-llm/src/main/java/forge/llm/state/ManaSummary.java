package forge.llm.state;

import java.util.ArrayList;
import java.util.List;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** One-line description of the mana a player could produce right now. */
public final class ManaSummary {
    private ManaSummary() {
    }

    public static String describe(Player player) {
        final List<String> sources = new ArrayList<>();
        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            if (c.isTapped()) {
                continue;
            }
            for (SpellAbility ma : c.getManaAbilities()) {
                final boolean needsTap = ma.getPayCosts() != null && ma.getPayCosts().hasTapCost();
                if (needsTap && c.isCreature() && c.isSick()) {
                    continue;
                }
                sources.add(c.getName() + " (" + String.join("/", c.getProducibleColors()) + ")");
                break;
            }
        }
        final StringBuilder sb = new StringBuilder();
        if (sources.isEmpty()) {
            sb.append("no untapped mana sources");
        } else {
            sb.append(sources.size()).append(" untapped mana source").append(sources.size() == 1 ? "" : "s").append(": ")
                    .append(String.join(", ", sources));
        }
        final String pool = player.getManaPool().toString();
        if (pool != null && !pool.isBlank() && player.getManaPool().totalMana() > 0) {
            sb.append("; floating in pool: ").append(pool);
        }
        return sb.toString();
    }
}
