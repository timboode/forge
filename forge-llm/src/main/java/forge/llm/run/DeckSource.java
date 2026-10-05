package forge.llm.run;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.util.MyRandom;

/**
 * Finds decks: by file path, by name in the user's Forge deck library, or "random" from the decks Forge ships
 * (the commander precons, or the quest precons for Constructed). Needs {@link FModel} to be initialised.
 */
public final class DeckSource {
    public static final String RANDOM = "random";

    private DeckSource() {
    }

    public static Deck resolve(String spec, Format format) {
        if (spec == null || spec.isBlank() || RANDOM.equalsIgnoreCase(spec.trim())) {
            return random(format);
        }
        final File file = new File(spec);
        Deck deck = file.isFile() ? DeckSerializer.fromFile(file) : null;
        if (deck == null && !file.isFile()) {
            deck = library(format).get(spec);
        }
        if (deck == null) {
            throw new IllegalArgumentException("no deck '" + spec + "': not a .dck file and not in your Forge "
                    + format.name().toLowerCase() + " deck library (list it with --list-decks)");
        }
        return checked(deck, format, spec);
    }

    /** A random deck from the ones that ship with Forge. */
    public static Deck random(Format format) {
        final File dir = new File(format.isCommander() ? ForgeConstants.COMMANDER_PRECON_DIR : ForgeConstants.QUEST_PRECON_DIR);
        final File[] files = dir.listFiles((d, name) -> name.endsWith(".dck"));
        if (files == null || files.length == 0) {
            throw new IllegalStateException("no shipped decks found in " + dir.getAbsolutePath());
        }
        Arrays.sort(files);
        for (int tries = 0; tries < 20; tries++) {
            final File pick = files[MyRandom.getRandom().nextInt(files.length)];
            final Deck deck = DeckSerializer.fromFile(pick);
            if (deck != null && (!format.isCommander() || !deck.getCommanders().isEmpty())) {
                return deck;
            }
        }
        throw new IllegalStateException("could not load a usable random deck from " + dir.getAbsolutePath());
    }

    /** Whether a library deck can be played in this format (a Commander game needs a commander Forge recognises). */
    public static boolean isPlayable(String libraryName, Format format) {
        final Deck deck = library(format).get(libraryName);
        return deck != null && (!format.isCommander() || !deck.getCommanders().isEmpty());
    }

    /** Names of the decks in the user's Forge deck library for this format. */
    public static List<String> libraryNames(Format format) {
        final List<String> names = new ArrayList<>(library(format).getItemNames());
        Collections.sort(names);
        return names;
    }

    private static forge.util.storage.IStorage<Deck> library(Format format) {
        return format.isCommander() ? FModel.getDecks().getCommander() : FModel.getDecks().getConstructed();
    }

    private static Deck checked(Deck deck, Format format, String spec) {
        if (format.isCommander() && deck.getCommanders().isEmpty()) {
            throw new IllegalArgumentException("deck '" + spec + "' has no usable commander: it has no [Commander] section, or its commander "
                    + "card is not known to this Forge build; a Commander game needs one (--list-decks marks such decks)");
        }
        return deck;
    }
}
