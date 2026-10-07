package forge.llm.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.tinylog.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The card-data MCP server (see forge-llm/mcp) reached over its streamable HTTP transport: every lookup is
 * a short-lived session - initialize, initialized, one tools/call - so nothing has to be kept alive between
 * decisions. Failures come back as a message the model can read, never as an exception that ends a decision.
 */
public final class McpCardLookup {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String TOOL_NAME = "lookupCard";
    private static final int MAX_CHARS = 6000;

    private final String url;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    public McpCardLookup(String url) {
        this.url = url;
    }

    /** The Oracle text and rulings of one card, or a readable error message. */
    public String lookup(String cardName) {
        try {
            final String sessionId = initialize();
            if (sessionId != null) {
                notifyInitialized(sessionId);
            }
            return callTool(sessionId, cardName);
        } catch (RuntimeException e) {
            Logger.info("card lookup via {} failed: {}", url, e.getMessage());
            return "Error: card lookup failed (" + e.getMessage() + "). Answer from what you know.";
        }
    }

    private String initialize() {
        final JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION);
        params.add("capabilities", new JsonObject());
        final JsonObject client = new JsonObject();
        client.addProperty("name", "forge-llm");
        client.addProperty("version", "1.0");
        params.add("clientInfo", client);
        final HttpResponse<String> response = post(message(1, "initialize", params), null);
        final JsonObject result = result(parse(response));
        Logger.debug("card server {} speaks MCP protocol {}", url,
                result.has("protocolVersion") ? result.get("protocolVersion").getAsString() : "?");
        return response.headers().firstValue("mcp-session-id").orElse(null);
    }

    private void notifyInitialized(String sessionId) {
        final JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", "notifications/initialized");
        post(notification.toString(), sessionId);
    }

    private String callTool(String sessionId, String cardName) {
        final JsonObject arguments = new JsonObject();
        arguments.addProperty("cardName", cardName);
        final JsonObject params = new JsonObject();
        params.addProperty("name", TOOL_NAME);
        params.add("arguments", arguments);
        final JsonObject result = result(parse(post(message(2, "tools/call", params), sessionId)));
        final StringBuilder sb = new StringBuilder();
        if (result.has("content") && result.get("content").isJsonArray()) {
            for (JsonElement element : result.getAsJsonArray("content")) {
                final JsonObject part = element.getAsJsonObject();
                if (part.has("type") && "text".equals(part.get("type").getAsString()) && part.has("text")) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(part.get("text").getAsString());
                }
            }
        }
        final String text = sb.toString();
        final String prefix = result.has("isError") && result.get("isError").getAsBoolean() ? "Error: " : "";
        final String answer = prefix + (text.isEmpty() ? "(the card server returned no text)" : text);
        return answer.length() > MAX_CHARS ? answer.substring(0, MAX_CHARS) + "..." : answer;
    }

    private static String message(int id, String method, JsonObject params) {
        final JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.addProperty("id", id);
        message.addProperty("method", method);
        message.add("params", params);
        return message.toString();
    }

    private HttpResponse<String> post(String body, String sessionId) {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (sessionId != null) {
            builder.header("mcp-session-id", sessionId);
        }
        final HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("cannot reach " + url + ": " + e.getMessage(), e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response;
    }

    /** The JSON-RPC envelope of a response body, which is plain JSON or a single Server-Sent-Events payload. */
    private static JsonObject parse(HttpResponse<String> response) {
        final String body = response.body();
        final String contentType = response.headers().firstValue("content-type").orElse("");
        if (contentType.contains("text/event-stream")) {
            JsonObject last = null;
            for (String line : body.split("\n")) {
                if (line.startsWith("data:")) {
                    final String data = line.substring(5).trim();
                    if (!data.isEmpty()) {
                        try {
                            last = JsonParser.parseString(data).getAsJsonObject();
                        } catch (RuntimeException ignored) {
                            // a chunk that is not complete JSON; the final data line is what matters
                        }
                    }
                }
            }
            if (last == null) {
                throw new IllegalStateException("no data in the event stream: " + body);
            }
            return last;
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private static JsonObject result(JsonObject envelope) {
        if (envelope.has("error") && envelope.get("error").isJsonObject()) {
            final JsonObject error = envelope.getAsJsonObject("error");
            throw new IllegalStateException(error.has("message") ? error.get("message").getAsString() : error.toString());
        }
        if (!envelope.has("result") || !envelope.get("result").isJsonObject()) {
            throw new IllegalStateException("no result in " + envelope);
        }
        return envelope.getAsJsonObject("result");
    }
}
