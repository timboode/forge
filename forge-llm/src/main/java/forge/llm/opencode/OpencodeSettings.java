package forge.llm.opencode;

import java.nio.file.Path;

/** Everything that configures how forge-llm talks to opencode. All fields are plain and mutable. */
public final class OpencodeSettings {

    // ---- the opencode server ---------------------------------------------------------------------------

    /** Path or name of the opencode executable; null = find "opencode" on the PATH. Ignored when {@link #serverUrl} is set. */
    public String executable;

    /** Attach to an opencode server that is already running instead of launching one (e.g. "http://127.0.0.1:4096"). */
    public String serverUrl;
    public String serverUsername = "opencode";
    /** Password of the server given in {@link #serverUrl}; null = the OPENCODE_SERVER_PASSWORD environment variable. */
    public String serverPassword;

    /**
     * When launching: give opencode its own config/data/state directories, so nothing of the user's opencode
     * setup (MCP servers, plugins, agents, history) is read or touched. Providers can still be borrowed with
     * {@link #providerConfigFile}.
     */
    public boolean isolate = true;

    /**
     * An opencode.json whose "provider" and "disabled_providers" sections are copied into the generated config
     * (typically the user's own ~/.config/opencode/opencode.json, to reuse its API keys). The keys are passed to
     * the child process in an environment variable, never written to disk.
     */
    public Path providerConfigFile;

    /** Directory for opencode's working files and log; null = a fresh temporary directory. */
    public Path workDir;

    public int startupTimeoutSeconds = 90;

    /** Passes --print-logs --log-level to opencode so its log file (in the work directory) explains slow or failing requests; null = quiet. */
    public String logLevel;

    // ---- the model -------------------------------------------------------------------------------------

    /** "providerID/modelID", as opencode names models, e.g. "openrouter/~deepseek/deepseek-flash-latest". */
    public String model = "openrouter/~deepseek/deepseek-flash-latest";

    /** Base URL of the LM Studio (OpenAI-compatible) server, used when {@link #model} starts with "lmstudio/". */
    public String lmStudioBaseUrl = "http://127.0.0.1:1234/v1";

    /** Context window of the model in tokens; 0 = ask opencode (or assume 16384 for an LM Studio model). */
    public int contextTokens;

    /** Tokens kept free for the model's answer (including its reasoning). */
    public int outputReserveTokens = 3072;

    /** Reasoning-effort variant understood by the model's provider (e.g. "high"); null = provider default. */
    public String variant;

    public double temperature = 0.3;

    /** Most model/tool round trips opencode may take to answer one prompt. */
    public int maxSteps = 4;

    // ---- card lookup -----------------------------------------------------------------------------------

    /** URL of the MCP server that offers the lookupCard tool (see forge-llm/mcp); null = no card lookup. */
    public String cardServerUrl;

    /** Name under which opencode registers that tool: "&lt;server key&gt;_lookupCard". */
    public static final String CARD_SERVER_KEY = "mtgcards";
    public static final String CARD_TOOL_NAME = CARD_SERVER_KEY + "_lookupCard";

    // ---- misc ------------------------------------------------------------------------------------------

    /** Keep finished opencode sessions (for inspecting them afterwards) instead of deleting them. */
    public boolean keepSessions;

    /** The agent definition injected into opencode's config. */
    public static final String AGENT_NAME = "forge-player";

    /** The agent that compresses transcripts into memory: short instructions, no tools. */
    public static final String SUMMARIZER_NAME = "forge-summarizer";

    public String providerId() {
        int slash = model.indexOf('/');
        return slash < 0 ? model : model.substring(0, slash);
    }

    public String modelId() {
        int slash = model.indexOf('/');
        return slash < 0 ? model : model.substring(slash + 1);
    }
}
