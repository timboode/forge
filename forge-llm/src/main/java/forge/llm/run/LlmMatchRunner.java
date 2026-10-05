package forge.llm.run;

import java.util.Collections;
import java.util.List;
import java.util.Random;

import forge.GuiDesktop;
import forge.game.Game;
import forge.game.GameLogEntry;
import forge.game.Match;
import forge.gui.GuiBase;
import forge.llm.agent.DecisionAgent;
import forge.llm.control.LobbyPlayerLlm;
import forge.model.FModel;
import forge.util.MyRandom;

/**
 * Headless entry point for trying out LLM-controlled players, modelled on Forge's own "sim" mode but without
 * touching it. Plays one or more games - Constructed or Commander, two to many seats, any mix of LLM-driven and
 * built-in-AI players - and prints the result, the controller statistics and, optionally, full agent transcripts.
 *
 * <pre>
 * Usage: LlmMatchRunner [--format constructed|commander] [--seats llm,ai,ai,ai] [--deck1 &lt;file|name|random&gt; ... --deckN ...]
 *          [--games N] [--seed S] [--timeout &lt;seconds per game&gt;] [--transcript &lt;dir&gt;] [--log]
 *          [--agent heuristic|pass|opencode] [--opponent llm]   (the latter is shorthand for --seats llm,llm)
 *          plus the agent and opencode options listed in {@link RunOptions}
 * </pre>
 * Seats without a deck get a random deck that ships with Forge (commander precons for Commander). Run from a
 * directory next to forge-gui (e.g. forge-llm/) so that Forge finds its resource files.
 */
public final class LlmMatchRunner {
    private LlmMatchRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
        final RunOptions options = RunOptions.parse(args);
        final Format format = options.format();
        final List<Seat.Kind> kinds = options.seatKinds(defaultSeats(options, format));
        if (kinds.contains(Seat.Kind.HUMAN)) {
            throw new IllegalArgumentException("a human seat needs the GUI: use forge.llm.run.PlayVsLlm");
        }

        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);

        final long seed = options.has("seed") ? Long.parseLong(options.get("seed")) : System.nanoTime();
        MyRandom.setRandom(new Random(seed));
        final int games = options.getInt("games", 1);
        final int timeoutSec = options.getInt("timeout", 600);
        final boolean printLog = options.has("log");

        final List<Seat> seats = MatchSetup.seats(options, format, kinds);
        final DecisionAgent baseAgent = options.createAgent();
        try {
            final MatchSetup.Table table = MatchSetup.table(format, seats, seat -> recordedFor(options, baseAgent, seat, seats),
                    options::playerConfig);

            System.out.println(format + " game, " + seats.size() + " seats, " + options.agentKind() + " agent, " + games
                    + " game(s), seed " + seed);
            seats.forEach(s -> System.out.println("  " + s.kind() + " " + s.name() + " - " + s.deck().getName()));

            final java.util.Map<String, Integer> wins = new java.util.LinkedHashMap<>();
            seats.forEach(s -> wins.put(s.name(), 0));
            final Match match = GameLauncher.newMatch(table.players(), format);
            for (int i = 0; i < games; i++) {
                final GameLauncher.Result result = GameLauncher.play(match, timeoutSec);
                final Game game = result.game();
                if (printLog) {
                    List<GameLogEntry> log = game.getGameLog().getLogEntries(null);
                    Collections.reverse(log);
                    log.forEach(System.out::println);
                }
                final String turn = "turn " + game.getPhaseHandler().getTurn() + ", " + result.millis() + " ms";
                if (game.getOutcome() == null || game.getOutcome().isDraw()) {
                    System.out.println("Game " + (i + 1) + ": draw" + (result.timedOut() ? " (timed out)" : "") + ", " + turn);
                } else {
                    final String winner = game.getOutcome().getWinningLobbyPlayer().getName();
                    wins.merge(winner, 1, Integer::sum);
                    System.out.println("Game " + (i + 1) + ": " + winner + " wins, " + turn);
                }
            }
            System.out.println("Wins: " + wins);
            for (LobbyPlayerLlm llm : table.llmSeats()) {
                System.out.println("Stats " + llm.getName() + ": " + llm.stats());
            }
        } finally {
            if (baseAgent instanceof AutoCloseable closeable) {
                closeable.close(); // stops the opencode server
            }
            System.out.flush();
        }
        System.exit(0);
    }

    private static String defaultSeats(RunOptions options, Format format) {
        if (format.isCommander()) {
            return "llm,ai,ai,ai";
        }
        return "llm".equals(options.get("opponent")) ? "llm,llm" : "llm,ai";
    }

    private static DecisionAgent recordedFor(RunOptions options, DecisionAgent base, Seat seat, List<Seat> seats) {
        try {
            return options.recorded(base, "seat" + (seats.indexOf(seat) + 1));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
