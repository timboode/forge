package forge.llm.rules;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The text a model can look up mid-game when it does not know a rule or a card: the lines of the rules
 * files it was given, plus (for exact card names) what the card database knows about that card - Oracle
 * text and rulings. A lookup is a pure function of the loaded text, so it never touches the running game.
 *
 * The model triggers a lookup with the numbered "Query MTG rules" option; the controller performs the
 * search and hands the result back in the same conversation.
 */
public final class RulesLibrary {
    /** One searchable file. */
    private record Source(String name, List<String> lines) { }

    /** The line cap promised to the model when the caller does not set one. */
    public static final int DEFAULT_MAX_LINES = 100;

    private final List<Source> sources;
    private final Function<String, String> cardFacts;
    private final boolean hasCardFacts;

    private RulesLibrary(List<Source> sources, Function<String, String> cardFacts, boolean hasCardFacts) {
        this.sources = sources;
        this.cardFacts = cardFacts;
        this.hasCardFacts = hasCardFacts;
    }

    /**
     * Loads the rules files; missing and empty files are skipped so a half-set-up installation still
     * answers with whatever it has. {@code cardFacts} maps an exact card name to a short facts block,
     * or null when the name is not a card.
     */
    public static RulesLibrary load(List<Path> files, Function<String, String> cardFacts) {
        final List<Source> sources = new ArrayList<>();
        for (Path file : files) {
            if (file == null || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (!lines.isEmpty()) {
                    sources.add(new Source(file.getFileName().toString(), List.copyOf(lines)));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read rules file " + file, e);
            }
        }
        return new RulesLibrary(List.copyOf(sources), cardFacts == null ? name -> null : cardFacts, cardFacts != null);
    }

    /** Whether any rules file is loaded (card lookups may still work on their own). */
    public boolean isEmpty() {
        return sources.isEmpty();
    }

    /** For logs: how many files and lines are loaded, and whether card facts are wired in. */
    public String describe() {
        int lines = 0;
        for (Source source : sources) {
            lines += source.lines().size();
        }
        return sources.size() + " rules file(s), " + lines + " lines"
                + (hasCardFacts ? " + card database facts" : "");
    }

    /**
     * Answers one lookup: a facts block for every phrase that names a card exactly, then up to
     * {@code maxLines} rules lines containing at least one phrase (lines matching more phrases first).
     * When more lines matched than are returned, the result says how many were left out.
     */
    public String search(List<String> phrases, int maxLines) {
        final List<String> trimmed = phrases.stream().map(String::trim).filter(p -> !p.isEmpty()).distinct().toList();
        if (trimmed.isEmpty()) {
            return "No search phrases were given. Answer like: ACTION <id> 'phrase one', 'phrase two'.";
        }
        final StringBuilder sb = new StringBuilder();
        for (String phrase : trimmed) {
            final String facts = cardFacts.apply(phrase);
            if (facts != null) {
                sb.append(facts).append('\n');
            }
        }

        final List<String> wanted = trimmed.stream().map(p -> p.toLowerCase(Locale.ROOT)).toList();
        record Hit(String line, int score) { }
        final List<Hit> hits = new ArrayList<>();
        for (Source source : sources) {
            for (String line : source.lines()) {
                final String lower = line.toLowerCase(Locale.ROOT);
                int score = 0;
                for (String phrase : wanted) {
                    if (lower.contains(phrase)) {
                        score++;
                    }
                }
                if (score > 0) {
                    hits.add(new Hit(line.trim(), score));
                }
            }
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed()); // stable: file order within equal scores
        final int cap = maxLines > 0 ? maxLines : DEFAULT_MAX_LINES;
        final int shown = Math.min(cap, hits.size());
        for (int i = 0; i < shown; i++) {
            sb.append(hits.get(i).line()).append('\n');
        }
        if (hits.size() > shown) {
            sb.append('(').append(shown).append(" of ").append(hits.size()).append(" matching lines are shown; ")
                    .append(hits.size() - shown)
                    .append(" more matched but were not returned - narrow the search with more specific phrases)\n");
        }
        if (sb.isEmpty()) {
            return "Nothing matched " + quoted(trimmed) + ". Try other phrases (short and specific), or an exact card name.";
        }
        return sb.toString();
    }

    private static String quoted(List<String> phrases) {
        final StringBuilder sb = new StringBuilder();
        for (String phrase : phrases) {
            sb.append(sb.length() == 0 ? "" : ", ").append('\'').append(phrase).append('\'');
        }
        return sb.toString();
    }
}
