package forge.llm.rules;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.testng.annotations.Test;

public class MtgjsonCardFactsTest {

    /** A miniature AllPrintings.sqlite: a card in two printings (identical text), two rulings. */
    private static Path database() throws Exception {
        final Path db = Files.createTempFile("cards-test", ".sqlite");
        db.toFile().deleteOnExit();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE cards (uuid TEXT, name TEXT, manaCost TEXT, type TEXT, text TEXT, side TEXT, layout TEXT)");
            st.execute("CREATE TABLE cardRulings (uuid TEXT, date TEXT, text TEXT)");
            st.execute("INSERT INTO cards VALUES ('u1', 'Rashmi, Eternities Crafter', '{4}{G}{U}', "
                    + "'Legendary Creature - Human Wizard', 'Whenever you cast your first spell each turn, reveal the "
                    + "top card of your library. You may cast it without paying its mana cost if it has lesser mana value.', 'a', 'normal')");
            st.execute("INSERT INTO cards VALUES ('u2', 'Rashmi, Eternities Crafter', '{4}{G}{U}', "
                    + "'Legendary Creature - Human Wizard', 'Whenever you cast your first spell each turn, reveal the "
                    + "top card of your library. You may cast it without paying its mana cost if it has lesser mana value.', 'a', 'normal')");
            st.execute("INSERT INTO cardRulings VALUES ('u1', '2016-09-30', 'The revealed card is revealed only to you.')");
            st.execute("INSERT INTO cardRulings VALUES ('u2', '2016-09-30', 'The revealed card is revealed only to you.')");
            st.execute("INSERT INTO cardRulings VALUES ('u1', '2016-09-30', 'If Rashmi leaves the battlefield, the permission ends.')");
            st.execute("INSERT INTO cards VALUES ('u3', 'Delver of Secrets // Insectile Aberration', '{U}', "
                    + "'Creature - Human Insect', 'At the beginning of your upkeep, look at the top card of your library.', 'a', 'transform')");
        }
        return db;
    }

    @Test
    public void aKnownCardYieldsItsTextAndDistinctRulings() throws Exception {
        final String result = MtgjsonCardFacts.open(database()).facts("Rashmi, Eternities Crafter");

        assertTrue(result.contains("CARD: Rashmi, Eternities Crafter"));
        assertTrue(result.contains("Legendary Creature - Human Wizard"));
        assertTrue(result.contains("Whenever you cast your first spell each turn"));
        assertTrue(result.contains("ruling 2016-09-30: The revealed card is revealed only to you."));
        assertTrue(result.contains("the permission ends"));
        assertEquals(countOccurrences(result, "revealed only to you"), 1,
                "the same ruling recorded for two printings is shown once");
    }

    @Test
    public void anExactNameIsMatchedCaseInsensitively() throws Exception {
        assertTrue(MtgjsonCardFacts.open(database()).facts("rashmi, eternities crafter").contains("CARD:"));
    }

    @Test
    public void theFrontHalfOfADoubleFacedCardMatches() throws Exception {
        final String result = MtgjsonCardFacts.open(database()).facts("Delver of Secrets");

        assertTrue(result.contains("CARD: Delver of Secrets"));
        assertTrue(result.contains("look at the top card"));
    }

    @Test
    public void anUnknownCardYieldsNothing() throws Exception {
        assertNull(MtgjsonCardFacts.open(database()).facts("Not A Real Card"));
    }

    @Test
    public void aBrokenDatabaseIsSimplyNoFacts() throws Exception {
        final Path notADatabase = Files.createTempFile("cards-broken", ".sqlite");
        notADatabase.toFile().deleteOnExit();
        Files.writeString(notADatabase, "this is not a database", StandardCharsets.UTF_8);

        assertNull(MtgjsonCardFacts.open(notADatabase).facts("Anything"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            count++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return count;
    }
}
