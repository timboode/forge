package forge.llm.state;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import forge.game.Game;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;

/** Reads the engine's game log in chronological order, as plain lines, skipping the noisiest entry types. */
public final class MatchLogView {
    /** Mana payments and per-phase markers add volume but no information the board state does not already carry. */
    private static final Set<GameLogEntryType> SKIPPED = EnumSet.of(GameLogEntryType.MANA, GameLogEntryType.PHASE);

    private MatchLogView() {
    }

    /** Number of raw log entries so far; use as a cursor for {@link #since}. */
    public static int cursor(Game game) {
        return game.getGameLog().getAllEntries().size();
    }

    /** All log lines from raw entry index {@code cursor} on. */
    public static List<String> since(Game game, int cursor) {
        List<GameLogEntry> all = game.getGameLog().getAllEntries();
        List<String> lines = new ArrayList<>();
        for (int i = Math.max(0, cursor); i < all.size(); i++) {
            GameLogEntry e = all.get(i);
            if (!SKIPPED.contains(e.type())) {
                lines.add(e.message());
            }
        }
        return lines;
    }

    /** Joins lines, keeping only the most recent {@code maxLines} (0 = unlimited) and saying so if truncated. */
    public static String render(List<String> lines, int maxLines) {
        if (lines.isEmpty()) {
            return "(nothing yet)";
        }
        StringBuilder sb = new StringBuilder();
        int from = 0;
        if (maxLines > 0 && lines.size() > maxLines) {
            from = lines.size() - maxLines;
            sb.append("... (").append(from).append(" earlier log lines omitted)\n");
        }
        for (int i = from; i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
