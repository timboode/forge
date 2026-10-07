package forge.llm.opencode;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in for {@code opencode serve}: the same paths, Basic authentication and message JSON shape for the
 * handful of calls forge-llm makes, with scripted model answers. The shape follows what the real server returns:
 * {@code {info:{tokens:{total,input,output,reasoning,cache:{read,write}}, error:{name,data:{message}}, finish},
 * parts:[step-start, reasoning, tool, text..., step-finish]}}.
 */
final class FakeOpencodeServer implements AutoCloseable {
    static final String USER = "opencode";
    static final String PASSWORD = "s3cret";

    /** What the model "said" in reply to one message, and what opencode reports about it. */
    record Answer(List<String> texts, int totalTokens, boolean reportTotal, int cacheTokens, String error, String errorName,
                  List<String> tools) {
        static Answer text(String text, int totalTokens) {
            return new Answer(List.of(text), totalTokens, true, 0, null, null, List.of());
        }

        /** The reply arrives as several text parts (e.g. text, a tool call, more text). */
        static Answer parts(int totalTokens, String... texts) {
            return new Answer(List.of(texts), totalTokens, true, 0, null, null, List.of("mtgcards_lookupCard"));
        }

        static Answer error(String message) {
            return new Answer(List.of(""), 0, true, 0, message, "APIError", List.of());
        }

        static Answer error(String name, String message) {
            return new Answer(List.of(""), 0, true, 0, message, name, List.of());
        }

        /** A provider that does not report a total and has served part of the prompt from its cache. */
        static Answer withoutTotal(String text, int inputTokens, int cacheTokens) {
            return new Answer(List.of(text), inputTokens, false, cacheTokens, null, null, List.of());
        }

        Answer withTools(String... tools) {
            return new Answer(texts, totalTokens, reportTotal, cacheTokens, error, errorName, List.of(tools));
        }
    }

    private final HttpServer server;
    private final AtomicInteger sessionCounter = new AtomicInteger();

    /** Session ids currently open, in creation order, with their titles. */
    final Map<String, String> openSessions = Collections.synchronizedMap(new LinkedHashMap<>());
    final List<String> deletedSessions = Collections.synchronizedList(new ArrayList<>());
    final List<String> abortedSessions = Collections.synchronizedList(new ArrayList<>());
    final List<String> requestLog = Collections.synchronizedList(new ArrayList<>());
    /** Every message POSTed. */
    final List<Message> messages = Collections.synchronizedList(new ArrayList<>());

    /** Decides the answer to each message; default: "ACTION 1". */
    volatile Function<Message, Answer> brain = m -> Answer.text("ACTION 1\nREASON: because", 100);
    volatile int providerContextLimit = 16384;
    /** Agents the server claims to have configured. */
    volatile List<String> agents = List.of(OpencodeSettings.AGENT_NAME, OpencodeSettings.SUMMARIZER_NAME, "build", "plan");

    record Message(String sessionId, JsonObject body) {
        String text() {
            return body.getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString();
        }

        String agent() {
            return body.get("agent").getAsString();
        }

        String providerId() {
            return body.getAsJsonObject("model").get("providerID").getAsString();
        }

        String modelId() {
            return body.getAsJsonObject("model").get("modelID").getAsString();
        }
    }

    FakeOpencodeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    OpencodeClient client() {
        return new OpencodeClient(url(), USER, PASSWORD);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        final String method = ex.getRequestMethod();
        final String path = ex.getRequestURI().getPath();
        requestLog.add(method + " " + path);
        final String expected = "Basic " + Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            reply(ex, 401, "");
            return;
        }
        final String body = read(ex.getRequestBody());

        if (method.equals("GET") && path.equals("/global/health")) {
            reply(ex, 200, "{\"healthy\":true}");
        } else if (method.equals("GET") && path.equals("/agent")) {
            final JsonArray list = new JsonArray();
            for (String name : agents) {
                final JsonObject a = new JsonObject();
                a.addProperty("name", name);
                list.add(a);
            }
            reply(ex, 200, list.toString());
        } else if (method.equals("GET") && path.equals("/config/providers")) {
            reply(ex, 200, "{\"providers\":[{\"id\":\"lmstudio\",\"models\":{\"google/gemma-4-e2b\":{\"limit\":{\"context\":"
                    + providerContextLimit + ",\"output\":2048}}}}]}");
        } else if (method.equals("POST") && path.equals("/session")) {
            final String id = "ses_" + sessionCounter.incrementAndGet();
            openSessions.put(id, JsonParser.parseString(body).getAsJsonObject().get("title").getAsString());
            reply(ex, 200, "{\"id\":\"" + id + "\"}");
        } else if (method.equals("POST") && path.matches("/session/[^/]+/message")) {
            final String id = path.split("/")[2];
            if (!openSessions.containsKey(id)) {
                reply(ex, 404, "{\"name\":\"NotFound\"}");
                return;
            }
            final Message message = new Message(id, JsonParser.parseString(body).getAsJsonObject());
            messages.add(message);
            reply(ex, 200, messageJson(id, brain.apply(message)));
        } else if (method.equals("DELETE") && path.matches("/session/[^/]+")) {
            final String id = path.split("/")[2];
            openSessions.remove(id);
            deletedSessions.add(id);
            reply(ex, 200, "true");
        } else if (method.equals("POST") && path.matches("/session/[^/]+/abort")) {
            abortedSessions.add(path.split("/")[2]);
            reply(ex, 200, "true");
        } else {
            reply(ex, 404, "");
        }
    }

    private static String messageJson(String sessionId, Answer answer) {
        final JsonObject info = new JsonObject();
        info.addProperty("id", "msg_1");
        info.addProperty("sessionID", sessionId);
        info.addProperty("role", "assistant");
        final JsonObject tokens = new JsonObject();
        if (answer.reportTotal()) {
            tokens.addProperty("total", answer.totalTokens());
        }
        tokens.addProperty("input", answer.reportTotal() ? answer.totalTokens() - 20 : answer.totalTokens());
        tokens.addProperty("output", 20);
        tokens.addProperty("reasoning", 0);
        final JsonObject cache = new JsonObject();
        cache.addProperty("read", answer.cacheTokens());
        cache.addProperty("write", 0);
        tokens.add("cache", cache);
        info.add("tokens", tokens);
        info.addProperty("finish", "stop");
        if (answer.error() != null) {
            final JsonObject error = new JsonObject();
            error.addProperty("name", answer.errorName());
            final JsonObject data = new JsonObject();
            data.addProperty("message", answer.error());
            error.add("data", data);
            info.add("error", error);
        }

        final JsonArray parts = new JsonArray();
        parts.add(typed("step-start"));
        final JsonObject reasoning = typed("reasoning");
        reasoning.addProperty("text", "thinking...");
        parts.add(reasoning);
        for (String tool : answer.tools()) {
            final JsonObject t = typed("tool");
            t.addProperty("tool", tool);
            parts.add(t);
        }
        for (String text : answer.texts()) {
            final JsonObject p = typed("text");
            p.addProperty("text", text);
            parts.add(p);
        }
        parts.add(typed("step-finish"));
        final JsonObject root = new JsonObject();
        root.add("info", info);
        root.add("parts", parts);
        return root.toString();
    }

    private static JsonObject typed(String type) {
        final JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return o;
    }

    private static String read(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }
}
