package forge.llm.run;

import java.util.Locale;

import forge.deck.Deck;

/** One place at the table: who plays it and with which deck. */
public record Seat(Kind kind, String name, Deck deck) {

    public enum Kind {
        /** The person at the keyboard (GUI launcher only). */
        HUMAN,
        /** An LLM-controlled player. */
        LLM,
        /** Forge's built-in AI. */
        AI;

        public static Kind parse(String s) {
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown seat '" + s + "' (human|llm|ai)");
            }
        }
    }
}
