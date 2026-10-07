package forge.llm.rules;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import org.testng.annotations.Test;

public class RulesLibraryTest {

    private static RulesLibrary library(String content, Function<String, String> cardFacts) throws IOException {
        final Path file = Files.createTempFile("rules-test", ".txt");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return RulesLibrary.load(List.of(file), cardFacts);
    }

    @Test
    public void searchMatchesLinesCaseInsensitivelyAndIgnoresNonMatchingLines() throws IOException {
        RulesLibrary lib = library("""
                701.26a A creature is summoning sick.
                509.1a The defending player declares blockers.
                Nothing relevant here.
                """, null);
        String result = lib.search(List.of("SUMMONING SICK", "blockers"), 100);
        assertTrue(result.contains("701.26a"));
        assertTrue(result.contains("509.1a"));
        assertFalse(result.contains("Nothing relevant"));
    }

    @Test
    public void linesMatchingMorePhrasesComeFirst() throws IOException {
        RulesLibrary lib = library("""
                Deathtouch alone line.
                Deathtouch and damage together line.
                """, null);
        String result = lib.search(List.of("deathtouch", "damage"), 100);
        assertTrue(result.indexOf("together line") < result.indexOf("alone line"));
    }

    @Test
    public void moreMatchesThanTheCapAreCountedAndNoted() throws IOException {
        final StringBuilder content = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            content.append("rule ").append(i).append(" matches phrase\n");
        }
        String result = library(content.toString(), null).search(List.of("matches phrase"), 100);
        long returned = result.lines().filter(l -> l.contains("matches phrase")).count();
        assertEquals(returned, 100);
        assertTrue(result.contains("100 of 150"));
        assertTrue(result.contains("50 more matched"));
    }

    @Test
    public void anExactCardNameReturnsTheCardFactsBlock() throws IOException {
        RulesLibrary lib = library("Some rules line.", name ->
                name.equalsIgnoreCase("Counterspell") ? "CARD: Counterspell {U}{U} - Instant" : null);
        String result = lib.search(List.of("Counterspell"), 100);
        assertTrue(result.contains("CARD: Counterspell"));
    }

    @Test
    public void aPhraseThatMatchesNothingExplainsWhatToDo() throws IOException {
        String result = library("Short text.", null).search(List.of("unicorn keyword"), 100);
        assertTrue(result.startsWith("Nothing matched"));
        assertTrue(result.contains("unicorn keyword"));
    }

    @Test
    public void blankPhrasesGetTheFormatHint() throws IOException {
        assertTrue(library("text", null).search(List.of("   "), 100).contains("ACTION <id>"));
    }

    @Test
    public void missingAndEmptyFilesAreSkippedWithoutFailing() throws IOException {
        final Path empty = Files.createTempFile("rules-empty", ".txt");
        try {
            RulesLibrary lib = RulesLibrary.load(List.of(Path.of("does-not-exist-rules.txt"), empty), null);
            assertTrue(lib.isEmpty());
            assertEquals(lib.describe(), "0 rules file(s), 0 lines");
        } finally {
            Files.deleteIfExists(empty);
        }
    }
}
