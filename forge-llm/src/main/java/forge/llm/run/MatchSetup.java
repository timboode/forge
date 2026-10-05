package forge.llm.run;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import forge.LobbyPlayer;
import forge.deck.Deck;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import forge.llm.agent.DecisionAgent;
import forge.llm.control.LlmPlayerConfig;
import forge.llm.control.LobbyPlayerLlm;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;

/**
 * Turns "who sits where with which deck" into the {@link RegisteredPlayer}s a Forge match is started with - in the
 * same way Forge's own lobby does (base game type Constructed plus the format's variants), so a Commander table
 * gets 40 life, a command zone and commander damage.
 */
public final class MatchSetup {
    private MatchSetup() {
    }

    /** The table, ready to hand to a {@code Match} or {@code HostedMatch}. */
    public record Table(List<RegisteredPlayer> players, List<LobbyPlayerLlm> llmSeats, RegisteredPlayer human) {
    }

    /**
     * Resolves the decks and names for the requested seats. A seat without a {@code --deckN} gets a random shipped
     * deck. Requires Forge's model ({@code FModel}) to be initialised.
     */
    public static List<Seat> seats(RunOptions options, Format format, List<Seat.Kind> kinds) {
        final List<Seat> seats = new ArrayList<>();
        final Set<String> usedNames = new HashSet<>();
        int humans = 0;
        for (int i = 0; i < kinds.size(); i++) {
            final Seat.Kind kind = kinds.get(i);
            if (kind == Seat.Kind.HUMAN && ++humans > 1) {
                throw new IllegalArgumentException("only one human seat is supported");
            }
            final Deck deck = DeckSource.resolve(options.deckSpec(i + 1), format);
            String name = switch (kind) {
                case HUMAN -> GamePlayerUtil.getGuiPlayer().getName();
                case LLM -> "LLM-" + shortName(deck);
                case AI -> "AI-" + shortName(deck);
            };
            if (!usedNames.add(name)) {
                name = name + " (" + (i + 1) + ")";
                usedNames.add(name);
            }
            seats.add(new Seat(kind, name, deck));
        }
        return seats;
    }

    /** "Abzan Armor [TDC] [2025]" -> "Abzan Armor" */
    static String shortName(Deck deck) {
        final String name = deck.getName() == null ? "deck" : deck.getName();
        final int bracket = name.indexOf('[');
        final String trimmed = (bracket > 0 ? name.substring(0, bracket) : name).trim();
        return trimmed.isEmpty() ? name : trimmed;
    }

    /**
     * @param agentFor   the agent to give an LLM seat (several seats may share one agent; their session keys differ)
     * @param configFor  a fresh per-player configuration for each LLM seat
     */
    public static Table table(Format format, List<Seat> seats, Function<Seat, DecisionAgent> agentFor,
                              Supplier<LlmPlayerConfig> configFor) {
        final List<RegisteredPlayer> players = new ArrayList<>();
        final List<LobbyPlayerLlm> llmSeats = new ArrayList<>();
        RegisteredPlayer human = null;
        for (Seat seat : seats) {
            final LobbyPlayer lobby;
            switch (seat.kind()) {
                case HUMAN -> lobby = GamePlayerUtil.getGuiPlayer();
                case LLM -> {
                    final LobbyPlayerLlm llm = new LobbyPlayerLlm(seat.name(), agentFor.apply(seat), configFor.get());
                    llm.setAvatarIndex(randomIndex(GuiBase.getInterface().getAvatarCount()));
                    llm.setSleeveIndex(randomIndex(GuiBase.getInterface().getSleevesCount()));
                    llmSeats.add(llm);
                    lobby = llm;
                }
                default -> lobby = GamePlayerUtil.createAiPlayer(seat.name(), randomIndex(GuiBase.getInterface().getAvatarCount()));
            }
            final RegisteredPlayer rp = RegisteredPlayer.forVariants(seats.size(), format.variants(), seat.deck(), null, false, null, null);
            rp.setPlayer(lobby);
            players.add(rp);
            if (seat.kind() == Seat.Kind.HUMAN) {
                human = rp;
            }
        }
        return new Table(players, llmSeats, human);
    }

    private static int randomIndex(int count) {
        return count <= 0 ? 0 : MyRandom.getRandom().nextInt(count);
    }
}
