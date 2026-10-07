package forge.llm.run;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import forge.llm.agent.DecisionAgent;
import forge.llm.agent.ModelAgent;
import forge.llm.agent.RecordingAgent;
import forge.llm.agent.stub.HeuristicStubAgent;
import forge.llm.agent.stub.PassOnlyStubAgent;
import forge.llm.control.LlmPlayerConfig;
import forge.llm.opencode.OpencodeAgent;
import forge.llm.opencode.OpencodeSettings;
import forge.llm.openai.RawInferenceSettings;
import forge.llm.openai.RawOpenAiAgent;
import forge.llm.rules.ForgeCardFacts;
import forge.llm.rules.MtgjsonCardFacts;
import forge.llm.rules.RulesLibrary;

/**
 * The command-line options shared by the headless runner, the GUI launcher and the opencode check, and the
 * objects built from them (agent, per-player configuration, opencode settings).
 *
 * <pre>
 * Game:    --format constructed|commander        --seats llm,ai,ai,...   --deck1 .. --deckN (file or library name or "random")
 *          --seed S   --transcript &lt;dir&gt;
 * Agent:   --agent heuristic|pass|opencode       --decision-timeout &lt;s&gt;   --max-log-lines N   --max-consultations N
 *          --max-retries N
 * Rules:   --rules file[;file]                   (rules text the model may look up; default: the two
 *                                                  "mtg ... rules.txt" files next to the launch scripts)
 *          --rules-lines N   --rules-queries N
 *          --card-db AllPrintings.sqlite          (card Oracle text + rulings for exact card names; default:
 *                                                  an AllPrintings.sqlite found next to the launch scripts,
 *                                                  the working directory or the CardDatabaseMCPServer checkout)
 * opencode:
 *          --oc-model providerID/modelID          (default openrouter/~deepseek/deepseek-flash-latest)
 *          --oc-lmstudio-url http://127.0.0.1:1234/v1     --oc-context &lt;tokens&gt;
 *          --oc-provider-config &lt;opencode.json&gt;  (borrow its "provider" section, e.g. ~/.config/opencode/opencode.json)
 *          --oc-variant &lt;name&gt;                    reasoning effort; default "high" for openrouter models
 *          --oc-output-reserve &lt;tokens&gt;           maximum output tokens; cannot be combined with --oc-variant
 *          --oc-exe &lt;path&gt;  --oc-keep-sessions  --oc-log-level DEBUG|INFO  --oc-work-dir &lt;dir&gt;
 *          --oc-url &lt;url&gt;   (use an opencode server that is already running; password from OPENCODE_SERVER_PASSWORD)
 *          --mcp-url &lt;url&gt;  (MCP server with the lookupCard tool, see forge-llm/mcp)
 *          --oc-llm-provider opencode|raw-inference-openai-compatible   (default opencode; the raw provider
 *                             calls the OpenAI-compatible /responses API at $OPENAI_API_ENDPOINT with $OPENAI_API_KEY)
 * </pre>
 */
public final class RunOptions {
    /** The two names --oc-llm-provider accepts: the opencode server and a raw OpenAI-compatible API. */
    public static final String LLM_PROVIDER_OPENCODE = "opencode";
    public static final String LLM_PROVIDER_RAW = "raw-inference-openai-compatible";

    private final Map<String, String> opts;
    private RulesLibrary cachedRules;
    private boolean rulesLoaded;
    private MtgjsonCardFacts cachedCardDb;
    private boolean cardDbLoaded;

    private RunOptions(Map<String, String> opts) {
        this.opts = opts;
    }

    public static RunOptions parse(String[] args) {
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
        return new RunOptions(m);
    }

    /** These options with {@code key} set to {@code value} unless it was given explicitly. */
    public RunOptions withDefault(String key, String value) {
        final Map<String, String> copy = new HashMap<>(opts);
        copy.putIfAbsent(key, value);
        return new RunOptions(copy);
    }

    public boolean has(String key) {
        return opts.containsKey(key);
    }

    public String get(String key) {
        return opts.get(key);
    }

    public String get(String key, String fallback) {
        return opts.getOrDefault(key, fallback);
    }

    public int getInt(String key, int fallback) {
        return opts.containsKey(key) ? Integer.parseInt(opts.get(key)) : fallback;
    }

    // ---- game ------------------------------------------------------------------------------------------

    public Format format() {
        return Format.parse(opts.get("format"));
    }

    /** The seats named by {@code --seats}, or by {@code defaultSpec} (comma separated human|llm|ai). */
    public List<Seat.Kind> seatKinds(String defaultSpec) {
        final List<Seat.Kind> kinds = new ArrayList<>();
        for (String s : opts.getOrDefault("seats", defaultSpec).split(",")) {
            kinds.add(Seat.Kind.parse(s));
        }
        if (kinds.size() < 2) {
            throw new IllegalArgumentException("a game needs at least two seats");
        }
        return kinds;
    }

    /** The deck given for seat {@code number} (1-based) with {@code --deckN}; null = none given (use a random one). */
    public String deckSpec(int number) {
        return opts.get("deck" + number);
    }

    public Path transcriptDir() throws IOException {
        if (!opts.containsKey("transcript")) {
            return null;
        }
        return Files.createDirectories(Path.of(opts.get("transcript")));
    }

    // ---- rules lookup ----------------------------------------------------------------------------------

    /**
     * The rules/card lookup the model may use through the "Query MTG rules" option. Rules files come from
     * {@code --rules} (separated by the platform's path separator) or, by default, from the two
     * {@code mtg ... rules.txt} files next to the launch scripts; an exact card name is answered from the
     * card database (see {@link #cardDatabase()}). Null when there is nothing to search.
     */
    public RulesLibrary rulesLibrary() {
        if (!rulesLoaded) {
            rulesLoaded = true;
            final List<Path> files = opts.containsKey("rules") ? explicitRulesFiles() : defaultRulesFiles();
            final MtgjsonCardFacts db = cardDatabase();
            final Function<String, String> cardFacts = name -> {
                final String facts = db == null ? null : db.facts(name);
                return facts != null ? facts : ForgeCardFacts.facts(name);
            };
            cachedRules = files.isEmpty() ? null : RulesLibrary.load(files, cardFacts);
        }
        return cachedRules;
    }

    private List<Path> explicitRulesFiles() {
        final List<Path> found = new ArrayList<>();
        for (String part : opts.get("rules").split(Pattern.quote(File.pathSeparator))) {
            if (!part.isBlank() && Files.isRegularFile(Path.of(part.trim()))) {
                found.add(Path.of(part.trim()));
            }
        }
        if (found.isEmpty()) {
            throw new IllegalArgumentException("no rules file found at " + opts.get("rules"));
        }
        return found;
    }

    private static List<Path> defaultRulesFiles() {
        final List<Path> found = new ArrayList<>();
        for (String name : List.of("mtg comprehensive rules.txt", "mtg commander rules.txt")) {
            for (String prefix : List.of("", "forge-llm/")) {
                final Path path = Path.of(prefix + name);
                if (Files.isRegularFile(path)) {
                    found.add(path);
                    break;
                }
            }
        }
        return found;
    }

    /**
     * The card database the lookup reads Oracle text and rulings from, or null when none is configured or
     * found. {@code --card-db} names one explicitly; otherwise the usual AllPrintings.sqlite locations are
     * tried: the working directory, the forge-llm checkout and the CardDatabaseMCPServer checkout.
     */
    private MtgjsonCardFacts cardDatabase() {
        if (!cardDbLoaded) {
            cardDbLoaded = true;
            if (opts.containsKey("card-db")) {
                final Path path = Path.of(opts.get("card-db"));
                if (!Files.isRegularFile(path)) {
                    throw new IllegalArgumentException("no card database at " + path);
                }
                cachedCardDb = MtgjsonCardFacts.open(path);
            } else {
                for (String candidate : List.of("AllPrintings.sqlite", "forge-llm/AllPrintings.sqlite",
                        "CardDatabaseMCPServer/AllPrintings.sqlite", "../CardDatabaseMCPServer/AllPrintings.sqlite")) {
                    final Path path = Path.of(candidate);
                    if (Files.isRegularFile(path)) {
                        cachedCardDb = MtgjsonCardFacts.open(path);
                        break;
                    }
                }
            }
        }
        return cachedCardDb;
    }

    // ---- agent -----------------------------------------------------------------------------------------

    public String agentKind() {
        return opts.getOrDefault("agent", "heuristic");
    }

    /** Creates the agent (for opencode this launches or attaches to the server). Close it when done if it is AutoCloseable. */
    public DecisionAgent createAgent() {
        return switch (agentKind()) {
            case "pass" -> new PassOnlyStubAgent();
            case "heuristic" -> new HeuristicStubAgent();
            case "opencode" -> LLM_PROVIDER_RAW.equals(llmProvider())
                    ? RawOpenAiAgent.connect(rawInferenceSettings())
                    : OpencodeAgent.connect(opencodeSettings());
            default -> throw new IllegalArgumentException("unknown agent '" + agentKind() + "' (heuristic|pass|opencode)");
        };
    }

    /** Wraps the agent so every prompt and answer is written to {@code <transcript dir>/<tag>.txt}; the agent itself if no transcript was asked for. */
    public DecisionAgent recorded(DecisionAgent base, String tag) throws IOException {
        final Path dir = transcriptDir();
        if (dir == null) {
            return base;
        }
        final String header = base instanceof ModelAgent model
                ? "======== STANDING INSTRUCTIONS (the system prompt of every session) ========\n" + model.systemPrompt() + "\n"
                : "";
        return new RecordingAgent(base, Files.newBufferedWriter(dir.resolve(tag + ".txt"), StandardCharsets.UTF_8), header);
    }

    public LlmPlayerConfig playerConfig() {
        final LlmPlayerConfig config = new LlmPlayerConfig();
        if (opts.containsKey("decision-timeout")) {
            config.decisionTimeoutSeconds = Integer.parseInt(opts.get("decision-timeout"));
        } else if ("opencode".equals(agentKind())) {
            config.decisionTimeoutSeconds = 900; // a local model can be slow; the call is abandoned after this
        }
        if (opts.containsKey("max-log-lines")) {
            config.maxLogLines = Integer.parseInt(opts.get("max-log-lines"));
        }
        if (opts.containsKey("max-consultations")) {
            config.maxConsultationsPerTurn = Integer.parseInt(opts.get("max-consultations"));
        }
        config.maxRetries = getInt("max-retries", config.maxRetries);
        config.rules = rulesLibrary();
        config.maxRulesResultLines = getInt("rules-lines", config.maxRulesResultLines);
        config.maxRulesQueriesPerDecision = getInt("rules-queries", config.maxRulesQueriesPerDecision);
        return config;
    }

    /** Which transport --agent opencode uses: the opencode server (default) or the raw OpenAI-compatible API. */
    public String llmProvider() {
        final String provider = opts.getOrDefault("oc-llm-provider", LLM_PROVIDER_OPENCODE);
        if (!LLM_PROVIDER_OPENCODE.equals(provider) && !LLM_PROVIDER_RAW.equals(provider)) {
            throw new IllegalArgumentException("unknown --oc-llm-provider '" + provider + "' ("
                    + LLM_PROVIDER_OPENCODE + "|" + LLM_PROVIDER_RAW + ")");
        }
        return provider;
    }

    /**
     * Settings for the raw provider. The endpoint and the API key are read from the environment only
     * ({@link RawInferenceSettings#ENDPOINT_ENV} / {@link RawInferenceSettings#API_KEY_ENV}); the model comes
     * from {@code --oc-model} as the provider names it (e.g. "inclusionai/ling-3.1-flash").
     */
    public RawInferenceSettings rawInferenceSettings() {
        final RawInferenceSettings s = new RawInferenceSettings();
        s.endpoint = System.getenv(RawInferenceSettings.ENDPOINT_ENV);
        s.apiKey = System.getenv(RawInferenceSettings.API_KEY_ENV);
        s.model = opts.get("oc-model");
        s.contextTokens = getInt("oc-context", 0);
        s.outputReserveTokens = getInt("oc-output-reserve", s.outputReserveTokens);
        s.cardServerUrl = opts.get("mcp-url");
        return s;
    }

    public OpencodeSettings opencodeSettings() {
        final OpencodeSettings s = new OpencodeSettings();
        s.model = opts.getOrDefault("oc-model", s.model);
        s.lmStudioBaseUrl = opts.getOrDefault("oc-lmstudio-url", s.lmStudioBaseUrl);
        if (opts.containsKey("oc-context")) {
            s.contextTokens = Integer.parseInt(opts.get("oc-context"));
        }
        if (opts.containsKey("oc-output-reserve")) {
            s.outputReserveTokens = Integer.parseInt(opts.get("oc-output-reserve"));
        }
        if (opts.containsKey("oc-provider-config")) {
            s.providerConfigFile = Path.of(opts.get("oc-provider-config"));
        }
        // A request may carry a reasoning effort or a reasoning-token budget, never both: --oc-variant and
        // --oc-output-reserve are mutually exclusive. With neither given, the hosted default reasons at high effort.
        final boolean variantGiven = opts.containsKey("oc-variant");
        final boolean outputReserveGiven = opts.containsKey("oc-output-reserve");
        if (variantGiven && outputReserveGiven) {
            throw new IllegalArgumentException("--oc-variant (reasoning effort) and --oc-output-reserve "
                    + "(max output tokens) cannot be combined: give only one of the two");
        }
        s.variant = variantGiven ? opts.get("oc-variant")
                : outputReserveGiven ? null
                : (s.model.startsWith("openrouter/") ? "high" : null);
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
}
