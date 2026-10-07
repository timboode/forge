package forge.llm.state;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import forge.game.Game;
import forge.game.GameType;
import forge.game.card.Card;
import forge.game.player.Player;

/**
 * Commander-specific facts for the prompt: where each player's commander is, how many times it has been cast
 * (and so what the commander tax is), and how much combat damage each player has taken from which commander.
 * All of it is public information.
 */
public final class CommanderSummary {
    /** 21 or more combat damage from a single commander loses the game. */
    public static final int LETHAL_COMMANDER_DAMAGE = 21;

    private CommanderSummary() {
    }

    /** Variants that use a command zone with commanders and commander damage. */
    private static final GameType[] COMMANDER_LIKE = {GameType.Commander, GameType.Oathbreaker, GameType.TinyLeaders, GameType.Brawl};

    public static boolean isCommanderGame(Game game) {
        for (GameType variant : COMMANDER_LIKE) {
            if (game.getRules().hasAppliedVariant(variant)) {
                return true;
            }
        }
        return false;
    }

    /** "Commander", "Brawl", ... or the plain game type when no commander-style variant is in play. */
    public static String formatName(Game game) {
        for (GameType variant : COMMANDER_LIKE) {
            if (game.getRules().hasAppliedVariant(variant)) {
                return variant.toString();
            }
        }
        return game.getRules().getGameType().toString();
    }

    /** Extra generic mana the next cast of this commander from the command zone costs (2 per earlier cast). */
    public static int commanderTax(Player owner, Card commander) {
        return 2 * owner.getCommanderCast(commander);
    }

    /** Lines about one player's commander(s) and the commander damage taken, for that player's block of the prompt. */
    public static List<String> linesFor(Player player) {
        final List<String> lines = new ArrayList<>();
        for (Card commander : player.getCommanders()) {
            final String where = commander.getZone() == null ? "nowhere" : commander.getZone().getZoneType().toString();
            final int casts = player.getCommanderCast(commander);
            final StringBuilder sb = new StringBuilder("Commander: ").append(commander.getName()).append(" (#")
                    .append(commander.getId()).append(") - in ").append(where).append(", cast ").append(casts)
                    .append(casts == 1 ? " time" : " times");
            if (casts > 0) {
                sb.append("; casting it from the command zone costs {").append(2 * casts).append("} more");
            }
            lines.add(sb.toString());
        }

        final List<String> damage = new ArrayList<>();
        for (Map.Entry<Card, Integer> e : player.getCommanderDamage()) {
            if (e.getValue() > 0) {
                final Player controller = e.getKey().getController();
                damage.add(e.getValue() + " from " + e.getKey().getName()
                        + (controller == null ? "" : " (" + controller.getName() + ")")
                        + (e.getValue() >= LETHAL_COMMANDER_DAMAGE ? " - LETHAL" : ""));
            }
        }
        if (!damage.isEmpty()) {
            lines.add("Commander damage taken (" + LETHAL_COMMANDER_DAMAGE + " from one commander is lethal): " + String.join("; ", damage));
        }
        return lines;
    }

    /** Short rules reminder for the first prompt of a session in a Commander game. */
    public static String rulesReminder(Game game) {
        final int opponents = game.getPlayers().size() - 1;
        return "Commander rules: you may cast your commander from the command zone (each earlier cast adds {2}); if it "
                + "would change zone you may return it to the command zone; a player who has taken " + LETHAL_COMMANDER_DAMAGE
                + " combat damage from a single commander loses. "
                + (opponents > 1
                ? "There are " + opponents + " opponents: choose which one each attacker attacks, watch every opponent's "
                  + "board and commander damage, and remember anyone can become the threat."
                : "");
    }
}
