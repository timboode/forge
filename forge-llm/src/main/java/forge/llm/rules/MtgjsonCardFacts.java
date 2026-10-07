package forge.llm.rules;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Card facts from an MTGJSON {@code AllPrintings.sqlite} database (the same file the optional card server
 * uses): the Oracle text of a card matched by exact name, plus the official rulings recorded for it.
 *
 * Read only, one short-lived connection per lookup, and every database problem simply yields no facts:
 * a lookup must never be able to break a game, and the rules files still answer on their own.
 */
public final class MtgjsonCardFacts {
    /** Most rulings shown for one card. */
    private static final int MAX_RULINGS = 10;

    /** A name matches its card row, and the front half of a double-faced/split card ("Front // Back"). */
    private static final String NAME_MATCH = "name = ? COLLATE NOCASE OR name LIKE ? || ' // %'";

    private final String url;

    private MtgjsonCardFacts(Path database) {
        this.url = "jdbc:sqlite:" + database.toAbsolutePath();
    }

    /** Opens the database; the caller has already checked that the file exists. */
    public static MtgjsonCardFacts open(Path database) {
        return new MtgjsonCardFacts(database);
    }

    /** The facts block for an exact card name, or null when the database does not know that card. */
    public String facts(String cardName) {
        try (Connection c = DriverManager.getConnection(url)) {
            final List<String> faces = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT manaCost, type, text FROM cards WHERE " + NAME_MATCH + " ORDER BY side")) {
                ps.setString(1, cardName);
                ps.setString(2, cardName);
                try (ResultSet rs = ps.executeQuery()) {
                    final Set<String> seen = new LinkedHashSet<>();
                    while (rs.next() && faces.size() < 2) {
                        final String text = flatten(rs.getString("text"));
                        final String face = flatten(rs.getString("manaCost")) + " - " + flatten(rs.getString("type"))
                                + (text.isEmpty() ? "" : " - " + text);
                        if (seen.add(face)) {
                            faces.add(face);
                        }
                    }
                }
            }
            if (faces.isEmpty()) {
                return null;
            }
            final StringBuilder sb = new StringBuilder();
            sb.append("CARD: ").append(cardName).append('\n');
            for (String face : faces) {
                sb.append("  ").append(face).append('\n');
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT DISTINCT date, text FROM cardRulings WHERE uuid IN "
                            + "(SELECT uuid FROM cards WHERE " + NAME_MATCH + ") ORDER BY date DESC")) {
                ps.setString(1, cardName);
                ps.setString(2, cardName);
                try (ResultSet rs = ps.executeQuery()) {
                    int shown = 0;
                    while (rs.next() && shown < MAX_RULINGS) {
                        shown++;
                        sb.append("  ruling ").append(flatten(rs.getString("date"))).append(": ")
                                .append(flatten(rs.getString("text"))).append('\n');
                    }
                }
            }
            return sb.toString();
        } catch (SQLException e) {
            return null;
        }
    }

    private static String flatten(String s) {
        return s == null ? "" : s.replace("\r", " ").replace("\n", " ").trim();
    }
}
