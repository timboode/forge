package forge.llm.run;

import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.swing.SwingUtilities;

import forge.GuiDesktop;
import forge.Singletons;
import forge.deck.Deck;
import forge.error.ExceptionHandler;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.gui.util.SOptionPane;
import forge.llm.agent.DecisionAgent;
import forge.llm.control.LobbyPlayerLlm;
import forge.model.FModel;
import forge.util.MyRandom;

/**
 * Play a real game in Forge's desktop GUI against LLM-controlled opponents (and, if you like, built-in AI ones).
 *
 * It opens the normal Forge window, then starts the match straight away with you in your seat and the other seats
 * as requested - you play with the usual Forge interface. Nothing in Forge is modified: this only starts the
 * desktop application the way {@code forge.view.Main} does and then hands a ready-made table to Forge's own
 * {@code HostedMatch}.
 *
 * <pre>
 * Usage: PlayVsLlm [--format commander|constructed] [--seats human,llm,ai,ai]
 *          [--deck1 &lt;file|library name|random&gt; ... --deckN ...]   (deck1 is yours; unnamed seats get a random shipped deck)
 *          [--agent opencode|heuristic|pass] [--seed S] [--transcript &lt;dir&gt;]
 *          plus the agent and opencode options listed in {@link RunOptions}
 *        PlayVsLlm --list-decks [--format commander]    lists the decks in your Forge deck library
 * </pre>
 * Defaults: Commander, seats {@code human,llm,ai,ai}, the LLM driven by the opencode agent. Exactly one seat
 * must be {@code human}. Run it from a directory next to forge-gui (e.g. forge-llm/).
 */
public final class PlayVsLlm {
    private static final long WINDOW_TIMEOUT_MILLIS = 180_000;

    private PlayVsLlm() {
    }

    public static void main(String[] args) {
        // The launcher is for real models: unlike the headless runner it defaults to the opencode agent.
        final RunOptions options = RunOptions.parse(args).withDefault("agent", "opencode");
        final Format format = options.has("format") ? options.format() : Format.COMMANDER;

        GuiBase.setInterface(new GuiDesktop());

        if (options.has("list-decks")) {
            System.setProperty("java.awt.headless", "true");
            FModel.initialize(null, null);
            System.out.println("Decks in your Forge " + format.name().toLowerCase() + " library:");
            DeckSource.libraryNames(format).forEach(n -> System.out.println("  " + n
                    + (DeckSource.isPlayable(n, format) ? "" : "   [not playable here: no usable commander]")));
            System.exit(0);
        }

        final List<Seat.Kind> kinds = options.seatKinds(format.isCommander() ? "human,llm,ai,ai" : "human,llm");
        if (kinds.stream().filter(k -> k == Seat.Kind.HUMAN).count() != 1) {
            System.err.println("Exactly one seat must be 'human' (got --seats " + options.get("seats") + ")");
            System.exit(2);
        }

        // Start the model first: a wrong opencode or model setting should fail here on the console, not in a window.
        final DecisionAgent baseAgent;
        try {
            baseAgent = options.createAgent();
        } catch (RuntimeException e) {
            System.err.println("Could not set up the LLM: " + e.getMessage());
            System.exit(3);
            return;
        }

        // From here on this mirrors forge.view.Main for the normal desktop start-up.
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        System.setProperty("sun.java2d.d3d", "false");
        ExceptionHandler.registerErrorHandling();
        Singletons.initializeOnce(true);
        Singletons.getControl().initialize();

        final Thread starter = new Thread(() -> startMatchWhenReady(options, format, kinds, baseAgent), "play-vs-llm-starter");
        starter.setDaemon(true);
        starter.start();
        // main() returns; the Swing event thread keeps the application alive until the window is closed
    }

    private static void startMatchWhenReady(RunOptions options, Format format, List<Seat.Kind> kinds, DecisionAgent baseAgent) {
        try {
            waitForMainWindow();
            if (options.has("seed")) {
                MyRandom.setRandom(new Random(Long.parseLong(options.get("seed"))));
            }
            final List<Seat> seats = MatchSetup.seats(options, format, kinds);
            final MatchSetup.Table table = MatchSetup.table(format, seats, seat -> recordedFor(options, baseAgent, seat, seats),
                    options::playerConfig);
            printTable(format, seats);

            FThreads.invokeInEdtNowOrLater(() -> {
                final HostedMatch hosted = GuiBase.getInterface().hostMatch();
                hosted.setOnMatchOver(() -> printStats(table.llmSeats()));
                final IGuiGame gui = GuiBase.getInterface().getNewGuiGame();
                final Map<RegisteredPlayer, IGuiGame> guis = Map.of(table.human(), gui);
                hosted.startMatch(GameType.Constructed, format.variants(), table.players(), guis);
            });
        } catch (RuntimeException | Error e) {
            e.printStackTrace();
            final String message = "Could not start the game: " + e.getMessage();
            System.err.println(message);
            SwingUtilities.invokeLater(() -> SOptionPane.showMessageDialog(message));
        }
    }

    private static void waitForMainWindow() {
        final long deadline = System.currentTimeMillis() + WINDOW_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (Singletons.getView() != null && Singletons.getView().getFrame() != null
                    && Singletons.getView().getFrame().isShowing()) {
                sleep(1_000); // let the home screen finish building
                return;
            }
            sleep(250);
        }
        throw new IllegalStateException("the Forge window did not appear within " + WINDOW_TIMEOUT_MILLIS / 1000 + "s");
    }

    private static DecisionAgent recordedFor(RunOptions options, DecisionAgent base, Seat seat, List<Seat> seats) {
        try {
            return options.recorded(base, "seat" + (seats.indexOf(seat) + 1));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void printTable(Format format, List<Seat> seats) {
        System.out.println(format + " game, " + seats.size() + " seats:");
        for (Seat s : seats) {
            final Deck deck = s.deck();
            System.out.println("  " + s.kind() + " " + s.name() + " - " + deck.getName());
        }
    }

    private static void printStats(List<LobbyPlayerLlm> llmSeats) {
        for (LobbyPlayerLlm llm : llmSeats) {
            System.out.println("Stats " + llm.getName() + ": " + llm.stats());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
