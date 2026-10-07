package forge.llm.opencode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Minimal client for the parts of opencode's HTTP server API that forge-llm needs: sessions, one blocking
 * "send a message and wait for the finished answer" call, and the model's context limit.
 */
public final class OpencodeClient {

    /** The finished answer to one message. */
    public record Reply(String text, String reasoning, int inputTokens, int outputTokens, int totalTokens,
                        List<String> toolCalls, String error, boolean contextOverflow, String finish) {
    }

    private static final Duration HARD_REQUEST_LIMIT = Duration.ofMinutes(30);

    private final URI base;
    private final String authorization;
    // HTTP/1.1 only: the JDK client otherwise tries an h2c upgrade on cleartext requests, which opencode (Bun) answers by hanging on POSTs
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();

    public OpencodeClient(String baseUrl, String username, String password) {
        this.base = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.authorization = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
    }

    public boolean isHealthy() {
        try {
            return send(get("global/health", Duration.ofSeconds(3))).statusCode() == 200;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** @return the id of a new, empty session */
    public String createSession(String title) {
        final JsonObject body = new JsonObject();
        body.addProperty("title", title);
        final JsonObject created = json(checked(send(post("session", body, Duration.ofSeconds(30)))));
        return created.get("id").getAsString();
    }

    /** Sends one user message to a session and blocks until the assistant has completely answered it. */
    public Reply sendMessage(String sessionId, String text, String providerId, String modelId, String agent, String variant) {
        final JsonObject body = new JsonObject();
        final JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text);
        final JsonArray parts = new JsonArray();
        parts.add(part);
        body.add("parts", parts);
        final JsonObject model = new JsonObject();
        model.addProperty("providerID", providerId);
        model.addProperty("modelID", modelId);
        body.add("model", model);
        body.addProperty("agent", agent);
        if (variant != null && !variant.isBlank() && !modelId.equals("qwen/qwen3.8-omni-flash")) {
            body.addProperty("variant", variant);
        }
        try {
            System.out.println("body: " + body.toString());
            return parseReply(json(checked(send(post("session/" + sessionId + "/message", body, HARD_REQUEST_LIMIT)))));
        } catch (OpencodeException e) {
            if (e.getCause() instanceof InterruptedException) {
                abortQuietly(sessionId); // the caller gave up: stop the model from generating for nobody
            }
            throw e;
        }
    }

    public void deleteSession(String sessionId) {
        uninterrupted(() -> send(HttpRequest.newBuilder(uri("session/" + sessionId)).header("Authorization", authorization)
                .timeout(Duration.ofSeconds(15)).DELETE().build())); // best effort: a leftover session only costs disk space
    }

    public void abortQuietly(String sessionId) {
        uninterrupted(() -> send(post("session/" + sessionId + "/abort", new JsonObject(), Duration.ofSeconds(10))));
    }

    /** Whether the server knows an agent of this name. */
    public boolean hasAgent(String name) {
        try {
            for (JsonElement a : JsonParser.parseString(checked(send(get("agent", Duration.ofSeconds(15))))).getAsJsonArray()) {
                if (name.equals(a.getAsJsonObject().get("name").getAsString())) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            // fall through
        }
        return false;
    }

    /**
     * Runs a best-effort cleanup call even when the calling thread has been interrupted - which is exactly when it
     * is needed (a model call was abandoned on timeout) and when the JDK HTTP client would refuse to send anything
     * without even trying.
     */
    private static void uninterrupted(Runnable call) {
        final boolean wasInterrupted = Thread.interrupted();
        try {
            call.run();
        } catch (RuntimeException ignored) {
            // best effort
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Context window of a configured model in tokens, or -1 if opencode does not say. */
    public int contextLimit(String providerId, String modelId) {
        try {
            final JsonObject providers = json(checked(send(get("config/providers", Duration.ofSeconds(15)))));
            for (JsonElement p : providers.getAsJsonArray("providers")) {
                final JsonObject provider = p.getAsJsonObject();
                if (!providerId.equals(provider.get("id").getAsString())) {
                    continue;
                }
                final JsonObject models = provider.getAsJsonObject("models");
                if (models.has(modelId)) {
                    final JsonObject limit = models.getAsJsonObject(modelId).getAsJsonObject("limit");
                    if (limit != null && limit.has("context")) {
                        return limit.get("context").getAsInt();
                    }
                }
            }
        } catch (RuntimeException e) {
            // fall through: unknown
        }
        return -1;
    }

    // ---- plumbing --------------------------------------------------------------------------------------

    static Reply parseReply(JsonObject message) {
        final JsonObject info = message.getAsJsonObject("info");
        final StringBuilder text = new StringBuilder();
        final StringBuilder reasoning = new StringBuilder();
        final List<String> tools = new ArrayList<>();
        for (JsonElement e : message.getAsJsonArray("parts")) {
            final JsonObject part = e.getAsJsonObject();
            switch (part.get("type").getAsString()) {
                case "text" -> text.append(text.length() > 0 ? "\n" : "").append(string(part, "text"));
                case "reasoning" -> reasoning.append(string(part, "text"));
                case "tool" -> tools.add(string(part, "tool"));
                default -> { }
            }
        }
        int input = 0;
        int output = 0;
        int total = 0;
        if (info.has("tokens") && info.get("tokens").isJsonObject()) {
            final JsonObject t = info.getAsJsonObject("tokens");
            input = intOf(t, "input");
            output = intOf(t, "output") + intOf(t, "reasoning");
            int cached = 0;
            if (t.has("cache") && t.get("cache").isJsonObject()) {
                cached = intOf(t.getAsJsonObject("cache"), "read") + intOf(t.getAsJsonObject("cache"), "write");
            }
            // "input" excludes tokens served from the provider's prompt cache, but they still fill the context window
            total = t.has("total") ? intOf(t, "total") : input + output + cached;
        }
        final String finish = info.has("finish") && !info.get("finish").isJsonNull() ? info.get("finish").getAsString() : null;
        return new Reply(text.toString().trim(), reasoning.toString().trim(), input, output, total, tools,
                describeError(info.get("error")), isContextOverflow(info.get("error")), finish);
    }

    /** opencode classifies provider "prompt too long" failures itself, whatever wording the provider used. */
    private static boolean isContextOverflow(JsonElement error) {
        return error != null && error.isJsonObject() && "ContextOverflowError".equals(string(error.getAsJsonObject(), "name"));
    }

    private static String describeError(JsonElement error) {
        if (error == null || error.isJsonNull()) {
            return null;
        }
        if (!error.isJsonObject()) {
            return error.toString();
        }
        final JsonObject o = error.getAsJsonObject();
        final String name = string(o, "name");
        String message = null;
        if (o.has("data") && o.get("data").isJsonObject()) {
            message = string(o.getAsJsonObject("data"), "message");
        }
        return message == null || message.isEmpty() ? (name.isEmpty() ? error.toString() : name) : name + ": " + message;
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static int intOf(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : 0;
    }

    private URI uri(String path) {
        return base.resolve(path);
    }

    private HttpRequest get(String path, Duration timeout) {
        return HttpRequest.newBuilder(uri(path)).header("Authorization", authorization).timeout(timeout).GET().build();
    }

    private HttpRequest post(String path, JsonObject body, Duration timeout) {
        return HttpRequest.newBuilder(uri(path)).header("Authorization", authorization)
                .header("Content-Type", "application/json").timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpencodeException("interrupted while waiting for opencode", e);
        } catch (IOException e) {
            throw new OpencodeException("opencode request failed: " + request.method() + " " + request.uri().getPath()
                    + " - " + e, e);
        }
    }

    private static String checked(HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            String body = response.body();
            throw new OpencodeException("opencode answered HTTP " + response.statusCode() + " for "
                    + response.request().method() + " " + response.request().uri().getPath()
                    + (body == null || body.isBlank() ? "" : ": " + body.substring(0, Math.min(body.length(), 500))));
        }
        return response.body();
    }

    private static JsonObject json(String body) {
        try {
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new OpencodeException("opencode returned something that is not a JSON object: "
                    + (body == null ? "null" : body.substring(0, Math.min(body.length(), 200))), e);
        }
    }
}
