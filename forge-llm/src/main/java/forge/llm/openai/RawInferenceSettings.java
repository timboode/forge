package forge.llm.openai;

/**
 * Everything that configures the raw OpenAI-compatible inference provider (tested against OpenRouter's
 * Responses API). The endpoint and the API key are never baked into the build: they are read from the
 * environment ({@link #ENDPOINT_ENV} / {@link #API_KEY_ENV}) when the settings are built from the command
 * line. All fields are plain and mutable.
 */
public final class RawInferenceSettings {
    /** Base URL of the API, e.g. {@code https://openrouter.ai/api/v1}; a request goes to {@code <endpoint>/responses}. */
    public static final String ENDPOINT_ENV = "OPENAI_API_ENDPOINT";

    /** The API key, sent as {@code Authorization: Bearer <key>}. */
    public static final String API_KEY_ENV = "OPENAI_API_KEY";

    public String endpoint;
    public String apiKey;

    /** Model slug as the provider names it, e.g. "inclusionai/ling-3.1-flash" (no opencode "provider/" prefix). */
    public String model;

    /** Context window in tokens; 0 = a conservative default. */
    public int contextTokens;

    /** Tokens kept free for the answer; also the request's max_output_tokens. */
    public int outputReserveTokens = 3072;

    public double temperature = 0.3;

    /** Most model/tool round trips per decision. */
    public int maxSteps = 4;

    /** URL of the MCP server offering lookupCard (see forge-llm/mcp); null = no card tool. */
    public String cardServerUrl;
}
