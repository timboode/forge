package forge.llm.run;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import forge.game.GameType;

/** The game formats the runner and launcher can set up. */
public enum Format {
    CONSTRUCTED,
    COMMANDER;

    /**
     * Game variants to apply on top of the base (Constructed) game type, exactly as Forge's own lobby does.
     * Computed on demand: {@link GameType} needs Forge's localizer, which is only available once the model is
     * initialised, and command-line parsing happens before that.
     */
    public Set<GameType> variants() {
        return this == COMMANDER ? EnumSet.of(GameType.Commander) : EnumSet.noneOf(GameType.class);
    }

    public boolean isCommander() {
        return this == COMMANDER;
    }

    public static Format parse(String name) {
        if (name == null) {
            return CONSTRUCTED;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown format '" + name + "' (constructed|commander)");
        }
    }
}
