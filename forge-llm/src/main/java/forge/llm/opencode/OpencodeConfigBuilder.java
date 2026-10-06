package forge.llm.opencode;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.llm.prompt.SystemPrompt;

/**
 * Builds the opencode configuration this integration runs with. It is handed to opencode in the
 * OPENCODE_CONFIG_CONTENT environment variable, which opencode merges on top of any other configuration.
 *
 * The central piece is a purpose-built agent, {@link OpencodeSettings#AGENT_NAME}: opencode's stock agents carry
 * a system prompt and the definitions of a dozen coding tools that together cost more than 10,000 tokens per
 * request - most of a small model's context window - and are useless for playing cards. The forge-player agent
 * has the game instructions as its prompt, every built-in tool switched off, and at most the card-lookup tool.
 */
public final class OpencodeConfigBuilder {
    private static final int DEFAULT_LM_STUDIO_CONTEXT = 16384;

    /** Added to the agent's prompt when the card-lookup tool is available. */
    static final String LOOKUP_ADDENDUM = """

            CARD LOOKUP
            You have one tool, %s(cardName), that returns a card's exact Oracle text and its official rulings.
            Use it only when you are genuinely unsure how a card works or interacts, and call it at most once or
            twice per decision - every call costs time. Card texts for the cards on the table are already in the
            prompt. After at most a couple of lookups, give your answer in the required format.
            """;

    /** Instructions of the summarizer agent; the transcript itself is the user message. */
    static final String SUMMARY_INSTRUCTIONS = """
            You are the memory of a Magic: The Gathering player. You are given notes about what the player did, with
            the reasons. Compress them into at most 120 words of plain text that the player can read at the start
            of a later turn: the plan being followed, what the opponent has and seems to be doing, cards being
            held back and why, and anything to remember. Reply with the summary only - no actions, no preamble.
            """;

    private OpencodeConfigBuilder() {
    }

    public static String agentPrompt(OpencodeSettings s) {
        String prompt = SystemPrompt.TEXT;
        if (s.cardServerUrl != null) {
            prompt += LOOKUP_ADDENDUM.formatted(OpencodeSettings.CARD_TOOL_NAME);
        }
        return prompt;
    }

    public static JsonObject build(OpencodeSettings s) {
        final JsonObject root = new JsonObject();
        root.addProperty("$schema", "https://opencode.ai/config.json");
        root.addProperty("autoupdate", false);
        root.addProperty("share", "disabled");
        root.addProperty("snapshot", false);
        root.addProperty("lsp", false);
        root.addProperty("formatter", false);
        final JsonObject compaction = new JsonObject();
        compaction.addProperty("auto", false); // conversation size is managed by forge-llm, per turn
        root.add("compaction", compaction);
        root.addProperty("model", s.model);
        root.addProperty("default_agent", OpencodeSettings.AGENT_NAME);

        final JsonObject providers = new JsonObject();
        if ("lmstudio".equals(s.providerId())) {
            providers.add("lmstudio", lmStudioProvider(s));
        } else if ("openrouter".equals(s.providerId())) {
            providers.add("openrouter", openRouterProvider(s));
        }
        copyProviders(s, root, providers);
        if (providers.size() > 0) {
            root.add("provider", providers);
        }

        if (s.cardServerUrl != null) {
            final JsonObject mcp = new JsonObject();
            final JsonObject card = new JsonObject();
            card.addProperty("type", "remote");
            card.addProperty("url", s.cardServerUrl);
            card.addProperty("enabled", true);
            card.addProperty("timeout", 30000);
            mcp.add(OpencodeSettings.CARD_SERVER_KEY, card);
            root.add("mcp", mcp);
        }

        final JsonObject agents = new JsonObject();
        agents.add(OpencodeSettings.AGENT_NAME, agent(s));
        agents.add(OpencodeSettings.SUMMARIZER_NAME, summarizer(s));
        root.add("agent", agents);
        return root;
    }

    private static JsonObject lmStudioProvider(OpencodeSettings s) {
        final JsonObject provider = new JsonObject();
        provider.addProperty("npm", "@ai-sdk/openai-compatible");
        provider.addProperty("name", "LM Studio (local)");
        final JsonObject options = new JsonObject();
        options.addProperty("baseURL", s.lmStudioBaseUrl);
        provider.add("options", options);

        final JsonObject limit = new JsonObject();
        limit.addProperty("context", s.contextTokens > 0 ? s.contextTokens : DEFAULT_LM_STUDIO_CONTEXT);
        limit.addProperty("output", s.outputReserveTokens);
        final JsonObject model = new JsonObject();
        model.addProperty("name", s.modelId());
        model.add("limit", limit);
        final JsonObject models = new JsonObject();
        models.add(s.modelId(), model);
        provider.add("models", models);
        return provider;
    }

    /**
     * OpenRouter, the hosted default: opencode names the model "openrouter/&lt;modelID&gt;" and reads the key from the
     * OPENROUTER_API_KEY environment variable (the launch scripts fill it from the local opencode installation's
     * auth store when it is not set, so it never has to touch disk).
     */
    private static JsonObject openRouterProvider(OpencodeSettings s) {
        final JsonObject provider = new JsonObject();
        provider.addProperty("npm", "@openrouter/ai-sdk-provider");
        provider.addProperty("name", "OpenRouter");
        final JsonObject options = new JsonObject();
        options.addProperty("apiKey", "{env:OPENROUTER_API_KEY}");
        provider.add("options", options);

        final JsonObject model = new JsonObject();
        model.addProperty("name", s.modelId());
        if (s.contextTokens > 0) {
            final JsonObject limit = new JsonObject();
            limit.addProperty("context", s.contextTokens);
            limit.addProperty("output", s.outputReserveTokens);
            model.add("limit", limit);
        }
        // The "high" effort variant is what RunOptions passes by default; define it so it always resolves.
        final JsonObject reasoning = new JsonObject();
        reasoning.addProperty("effort", "high");
        final JsonObject high = new JsonObject();
        high.add("reasoning", reasoning);
        final JsonObject variants = new JsonObject();
        variants.add("high", high);
        model.add("variants", variants);
        final JsonObject models = new JsonObject();
        models.add(s.modelId(), model);
        provider.add("models", models);
        return provider;
    }

    private static void copyProviders(OpencodeSettings s, JsonObject root, JsonObject providers) {
        if (s.providerConfigFile == null) {
            return;
        }
        final JsonObject source;
        try (Reader in = Files.newBufferedReader(s.providerConfigFile, StandardCharsets.UTF_8)) {
            source = JsonParser.parseReader(in).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            throw new OpencodeException("cannot read provider config " + s.providerConfigFile, e);
        }
        if (source.has("provider")) {
            for (var e : source.getAsJsonObject("provider").entrySet()) {
                if (!providers.has(e.getKey())) { // a provider generated above wins
                    providers.add(e.getKey(), e.getValue());
                }
            }
        }
        final JsonElement disabled = source.get("disabled_providers");
        if (disabled instanceof JsonArray) {
            root.add("disabled_providers", disabled);
        }
    }

    private static JsonObject summarizer(OpencodeSettings s) {
        final JsonObject agent = new JsonObject();
        agent.addProperty("description", "Compresses a player's notes into a short memory");
        agent.addProperty("mode", "primary");
        agent.addProperty("prompt", SUMMARY_INSTRUCTIONS);
        agent.addProperty("temperature", 0.2);
        final JsonObject tools = new JsonObject();
        tools.addProperty("*", false);
        agent.add("tools", tools);
        final JsonObject permission = new JsonObject();
        permission.addProperty("*", "deny");
        agent.add("permission", permission);
        return agent;
    }

    private static JsonObject agent(OpencodeSettings s) {
        final JsonObject agent = new JsonObject();
        agent.addProperty("description", "Plays one seat of a Magic: The Gathering game run by Forge");
        agent.addProperty("mode", "primary");
        agent.addProperty("prompt", agentPrompt(s));
        agent.addProperty("temperature", s.temperature);
        agent.addProperty("steps", s.maxSteps);

        final JsonObject tools = new JsonObject();
        tools.addProperty("*", false);
        final JsonObject permission = new JsonObject();
        permission.addProperty("*", "deny");
        if (s.cardServerUrl != null) {
            tools.addProperty(OpencodeSettings.CARD_TOOL_NAME, true);
            permission.addProperty(OpencodeSettings.CARD_TOOL_NAME, "allow");
        }
        agent.add("tools", tools);
        agent.add("permission", permission);
        return agent;
    }
}
