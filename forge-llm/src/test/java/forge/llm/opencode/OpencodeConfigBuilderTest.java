package forge.llm.opencode;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.testng.annotations.Test;

import com.google.gson.JsonObject;

import forge.llm.prompt.SystemPrompt;

public class OpencodeConfigBuilderTest {

    @Test
    public void localLmStudioModelGetsItsOwnProviderWithTheContextWindowItWasGiven() {
        OpencodeSettings s = new OpencodeSettings();
        s.model = "lmstudio/google/gemma-4-e2b";
        s.contextTokens = 16384;
        JsonObject config = OpencodeConfigBuilder.build(s);

        assertEquals(config.get("model").getAsString(), "lmstudio/google/gemma-4-e2b");
        JsonObject provider = config.getAsJsonObject("provider").getAsJsonObject("lmstudio");
        assertEquals(provider.getAsJsonObject("options").get("baseURL").getAsString(), "http://127.0.0.1:1234/v1");
        JsonObject model = provider.getAsJsonObject("models").getAsJsonObject("google/gemma-4-e2b");
        assertEquals(model.getAsJsonObject("limit").get("context").getAsInt(), 16384);
    }

    @Test
    public void theDefaultModelIsDeepSeekFlashOnOpenRouterWithAHighEffortVariant() {
        JsonObject config = OpencodeConfigBuilder.build(new OpencodeSettings());

        assertEquals(config.get("model").getAsString(), "openrouter/~deepseek/deepseek-flash-latest");
        JsonObject provider = config.getAsJsonObject("provider").getAsJsonObject("openrouter");
        assertEquals(provider.get("npm").getAsString(), "@openrouter/ai-sdk-provider");
        assertEquals(provider.getAsJsonObject("options").get("apiKey").getAsString(), "{env:OPENROUTER_API_KEY}");
        JsonObject model = provider.getAsJsonObject("models").getAsJsonObject("~deepseek/deepseek-flash-latest");
        assertEquals(model.getAsJsonObject("variants").getAsJsonObject("high")
                .getAsJsonObject("reasoning").get("effort").getAsString(), "high");
    }

    @Test
    public void theAgentCarriesTheGameInstructionsAndNoCodingTools() {
        JsonObject agent = OpencodeConfigBuilder.build(new OpencodeSettings())
                .getAsJsonObject("agent").getAsJsonObject(OpencodeSettings.AGENT_NAME);

        assertTrue(agent.get("prompt").getAsString().startsWith(SystemPrompt.TEXT));
        JsonObject tools = agent.getAsJsonObject("tools");
        assertFalse(tools.get("*").getAsBoolean(), "every built-in tool is off: their definitions alone would eat the context window");
        assertEquals(tools.size(), 1, "no tool is enabled when there is no card server");
        assertEquals(agent.getAsJsonObject("permission").get("*").getAsString(), "deny");
        assertEquals(OpencodeConfigBuilder.build(new OpencodeSettings()).get("default_agent").getAsString(), OpencodeSettings.AGENT_NAME);
    }

    @Test
    public void theSummarizerAgentHasItsOwnShortInstructionsAndNoTools() {
        JsonObject agent = OpencodeConfigBuilder.build(new OpencodeSettings())
                .getAsJsonObject("agent").getAsJsonObject(OpencodeSettings.SUMMARIZER_NAME);

        assertEquals(agent.get("prompt").getAsString(), OpencodeConfigBuilder.SUMMARY_INSTRUCTIONS);
        assertFalse(agent.get("prompt").getAsString().contains("ACTION"), "the player's answer format must not leak into summaries");
        assertEquals(agent.getAsJsonObject("tools").size(), 1);
        assertFalse(agent.getAsJsonObject("tools").get("*").getAsBoolean());
        assertEquals(agent.getAsJsonObject("permission").get("*").getAsString(), "deny");
        assertFalse(agent.has("steps"), "a one-step limit makes opencode answer with nothing at all");
    }

    @Test
    public void noCardServerMeansNoMcpEntryAndNoLookupInstructions() {
        JsonObject config = OpencodeConfigBuilder.build(new OpencodeSettings());
        assertFalse(config.has("mcp"));
        assertFalse(OpencodeConfigBuilder.agentPrompt(new OpencodeSettings()).contains("lookupCard"));
    }

    @Test
    public void aCardServerIsRegisteredAndOnlyItsLookupToolIsEnabled() {
        OpencodeSettings s = new OpencodeSettings();
        s.cardServerUrl = "http://127.0.0.1:3041/";
        JsonObject config = OpencodeConfigBuilder.build(s);

        JsonObject mcp = config.getAsJsonObject("mcp").getAsJsonObject(OpencodeSettings.CARD_SERVER_KEY);
        assertEquals(mcp.get("type").getAsString(), "remote");
        assertEquals(mcp.get("url").getAsString(), "http://127.0.0.1:3041/");

        JsonObject agent = config.getAsJsonObject("agent").getAsJsonObject(OpencodeSettings.AGENT_NAME);
        assertTrue(agent.getAsJsonObject("tools").get(OpencodeSettings.CARD_TOOL_NAME).getAsBoolean());
        assertFalse(agent.getAsJsonObject("tools").get("*").getAsBoolean(), "deck-building tools of the same server stay off");
        assertEquals(agent.getAsJsonObject("permission").get(OpencodeSettings.CARD_TOOL_NAME).getAsString(), "allow");
        assertTrue(agent.get("prompt").getAsString().contains(OpencodeSettings.CARD_TOOL_NAME));
    }

    @Test
    public void providersAreBorrowedFromAnotherConfigButAGeneratedLmStudioProviderWins() throws IOException {
        Path file = Files.createTempFile("opencode-providers", ".json");
        try {
            Files.writeString(file, """
                    { "provider": { "mine": { "options": { "baseURL": "https://example.invalid/v1" } },
                                    "lmstudio": { "options": { "baseURL": "http://wrong" } } },
                      "disabled_providers": ["anthropic"],
                      "mcp": { "something-heavy": { "type": "local", "command": ["x"] } } }
                    """);
            OpencodeSettings s = new OpencodeSettings();
            s.model = "lmstudio/google/gemma-4-e2b";
            s.providerConfigFile = file;
            JsonObject config = OpencodeConfigBuilder.build(s);

            JsonObject providers = config.getAsJsonObject("provider");
            assertTrue(providers.has("mine"));
            assertEquals(providers.getAsJsonObject("lmstudio").getAsJsonObject("options").get("baseURL").getAsString(), "http://127.0.0.1:1234/v1");
            assertEquals(config.getAsJsonArray("disabled_providers").get(0).getAsString(), "anthropic");
            assertFalse(config.has("mcp"), "only providers are borrowed - never the other config's MCP servers or plugins");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void anUnreadableProviderConfigIsReportedClearly() {
        OpencodeSettings s = new OpencodeSettings();
        s.providerConfigFile = Path.of("does-not-exist-opencode.json");
        assertThrows(OpencodeException.class, () -> OpencodeConfigBuilder.build(s));
    }

    @Test
    public void modelReferencesSplitOnTheFirstSlashOnly() {
        OpencodeSettings s = new OpencodeSettings();
        s.model = "curator-dev/deepseek-v4.1-flash";
        assertEquals(s.providerId(), "curator-dev");
        assertEquals(s.modelId(), "deepseek-v4.1-flash");
        s.model = "lmstudio/google/gemma-4-e2b";
        assertEquals(s.modelId(), "google/gemma-4-e2b");
    }
}
