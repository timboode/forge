package forge.llm.run;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import forge.GuiDesktop;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameLogEntry;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import forge.llm.agent.DecisionAgent;
import forge.llm.agent.RecordingAgent;
import forge.llm.agent.stub.HeuristicStubAgent;
import forge.llm.agent.stub.PassOnlyStubAgent;
import forge.llm.control.LlmPlayerConfig;
import forge.llm.control.LobbyPlayerLlm;
import forge.llm.opencode.OpencodeAgent;
import forge.llm.opencode.OpencodeSettings;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;

/**
 * Headless entry point for trying out an LLM-controlled player, modelled on Forge's own "sim" mode but
 * without touching it. Plays one or more games of an LLM-driven player against the built-in AI (or another
 * LLM player) and prints the result, the controller statistics and, optionally, full agent transcripts.
 *
 * <pre>
 * Usage: LlmMatchRunner --deck1 &lt;file.dck&gt; --deck2 &lt;file.dck&gt;
 *          [--agent heuristic|pass|opencode] [--opponent ai|llm] [--games N] [--seed S]
 *          [--transcript &lt;dir&gt;] [--timeout &lt;seconds per game&gt;] [--log]
 *          [--decision-timeout &lt;seconds per model call&gt;] [--max-log-lines N] [--max-consultations N]
 *
 *        with --agent opencode (needs opencode on the PATH; it is launched and stopped by the runner):
 *          [--oc-model providerID/modelID]        default lmstudio/google/gemma-4-e2b
 *          [--oc-lmstudio-url http://127.0.0.1:1234/v1]
 *          [--oc-context &lt;tokens&gt;]                 default: ask opencode (16384 for LM Studio)
 *          [--oc-provider-config &lt;opencode.json&gt;]  borrow its "provider" section, e.g. ~/.config/opencode/opencode.json
 *          [--oc-variant &lt;name&gt;] [--oc-exe &lt;path&gt;] [--oc-keep-sessions] [--oc-log-level DEBUG|INFO] [--oc-work-dir <dir>]
 *          [--oc-url &lt;url&gt;]                        use an opencode server that is already running (password: env OPENCODE_SERVER_PASSWORD)
 *          [--mcp-url &lt;url&gt;]                       MCP server with the lookupCard tool (forge-llm/mcp)
 * </pre>
 * Run from a directory next to forge-gui (e.g. forge-llm/) so that Forge finds its resource files.
 */
public final class LlmMatchRunner {
    private LlmMatchRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
        final Map<String, String> opts = parse(args);
        if (!opts.containsKey("deck1") || !opts.containsKey("deck2")) {
            System.out.println("Usage: LlmMatchRunner --deck1 <file.dck> --deck2 <file.dck> [--agent heuristic|pass|opencode] "
                    + "[--opponent ai|llm] [--games N] [--seed S] [--transcript <dir>] [--timeout sec] [--log] "
                    + "[--oc-model provider/model] [--oc-context tokens] [--mcp-url url] ... (see the class comment)");
            return;
        }

        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);

        final long seed = opts.containsKey("seed") ? Long.parseLong(opts.get("seed")) : System.nanoTime();
        MyRandom.setRandom(new Random(seed));
        final int games = Integer.parseInt(opts.getOrDefault("games", "1"));
        final int timeoutSec = Integer.parseInt(opts.getOrDefault("timeout", "600"));
        final boolean printLog = opts.containsKey("log");
        final String agentKind = opts.getOrDefault("agent", "heuristic");
        final Path transcriptDir = opts.containsKey("transcript") ? Path.of(opts.get("transcript")) : null;
        if (transcriptDir != null) {
            Files.createDirectories(transcriptDir);
        }

        final Deck deck1 = load(opts.get("deck1"));
        final Deck deck2 = load(opts.get("deck2"));

        final DecisionAgent baseAgent = baseAgent(agentKind, opts);
        try {
            final LobbyPlayerLlm llm1 = new LobbyPlayerLlm("LLM-" + deck1.getName(),
                    recorded(baseAgent, transcriptDir, "player1"), playerConfig(opts));
            final List<RegisteredPlayer> players = new ArrayList<>();
            players.add(new RegisteredPlayer(deck1).setPlayer(llm1));

            LobbyPlayerLlm llm2 = null;
            if ("llm".equals(opts.get("opponent"))) {
                llm2 = new LobbyPlayerLlm("LLM2-" + deck2.getName(), recorded(baseAgent, transcriptDir, "player2"), playerConfig(opts));
                players.add(new RegisteredPlayer(deck2).setPlayer(llm2));
            } else {
                players.add(new RegisteredPlayer(deck2).setPlayer(GamePlayerUtil.createAiPlayer("AI-" + deck2.getName(), 1)));
            }

            System.out.println("LLM player (" + agentKind + " agent) '" + llm1.getName() + "' vs '"
                    + players.get(1).getPlayer().getName() + "', " + games + " game(s), seed " + seed);

            int llmWins = 0;
            final Match match = GameLauncher.newMatch(players);
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
                    if (winner.equals(llm1.getName())) {
                        llmWins++;
                    }
                    System.out.println("Game " + (i + 1) + ": " + winner + " wins, " + turn);
                }
            }
            System.out.println("LLM player " + llm1.getName() + " won " + llmWins + " of " + games);
            System.out.println("Stats " + llm1.getName() + ": " + llm1.stats());
            if (llm2 != null) {
                System.out.println("Stats " + llm2.getName() + ": " + llm2.stats());
            }
        } finally {
            if (baseAgent instanceof AutoCloseable closeable) {
                closeable.close(); // stops the opencode server
            }
            System.out.flush();
        }
        System.exit(0);
    }

    private static LlmPlayerConfig playerConfig(Map<String, String> opts) {
        final LlmPlayerConfig config = new LlmPlayerConfig();
        if (opts.containsKey("decision-timeout")) {
            config.decisionTimeoutSeconds = Integer.parseInt(opts.get("decision-timeout"));
        } else if ("opencode".equals(opts.get("agent"))) {
            config.decisionTimeoutSeconds = 900; // a local model can be slow; the call is abandoned after this
        }
        if (opts.containsKey("max-log-lines")) {
            config.maxLogLines = Integer.parseInt(opts.get("max-log-lines"));
        }
        if (opts.containsKey("max-consultations")) {
            config.maxConsultationsPerTurn = Integer.parseInt(opts.get("max-consultations"));
        }
        return config;
    }

    private static DecisionAgent baseAgent(String kind, Map<String, String> opts) {
        return switch (kind) {
            case "pass" -> new PassOnlyStubAgent();
            case "heuristic" -> new HeuristicStubAgent();
            case "opencode" -> OpencodeAgent.connect(opencodeSettings(opts));
            default -> throw new IllegalArgumentException("unknown agent '" + kind + "' (heuristic|pass|opencode)");
        };
    }

    static OpencodeSettings opencodeSettings(Map<String, String> opts) {
        final OpencodeSettings s = new OpencodeSettings();
        s.model = opts.getOrDefault("oc-model", s.model);
        s.lmStudioBaseUrl = opts.getOrDefault("oc-lmstudio-url", s.lmStudioBaseUrl);
        if (opts.containsKey("oc-context")) {
            s.contextTokens = Integer.parseInt(opts.get("oc-context"));
        }
        if (opts.containsKey("oc-provider-config")) {
            s.providerConfigFile = Path.of(opts.get("oc-provider-config"));
        }
        s.variant = opts.get("oc-variant");
        s.executable = opts.get("oc-exe");
        s.keepSessions = opts.containsKey("oc-keep-sessions");
        s.logLevel = opts.get("oc-log-level");
        if (opts.containsKey("oc-work-dir")) {
            s.workDir = Path.of(opts.get("oc-work-dir"));
        }
        s.serverUrl = opts.get("oc-url");
        s.cardServerUrl = opts.get("mcp-url");
        return s;
    }

    private static DecisionAgent recorded(DecisionAgent base, Path transcriptDir, String tag) throws IOException {
        if (transcriptDir == null) {
            return base;
        }
        final String header = base instanceof OpencodeAgent opencode
                ? "======== STANDING INSTRUCTIONS (the system prompt of every session) ========\n" + opencode.systemPrompt() + "\n"
                : "";
        return new RecordingAgent(base, Files.newBufferedWriter(transcriptDir.resolve(tag + ".txt"), StandardCharsets.UTF_8), header);
    }

    private static Deck load(String path) {
        final Deck deck = DeckSerializer.fromFile(new File(path));
        if (deck == null) {
            throw new IllegalArgumentException("could not load deck " + path);
        }
        return deck;
    }

    static Map<String, String> parse(String[] args) {
        final Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument " + args[i]);
            }
            final String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                m.put(key, args[++i]);
            } else {
                m.put(key, "true");
            }
        }
        return m;
    }
}
