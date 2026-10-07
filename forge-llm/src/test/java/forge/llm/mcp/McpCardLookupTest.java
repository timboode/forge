package forge.llm.mcp;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

public class McpCardLookupTest {
    private HttpServer server;
    private final List<JsonObject> requests = new ArrayList<>();
    private final List<String> sessionHeaders = new ArrayList<>();

    @BeforeMethod
    public void startFakeMcpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(JsonParser.parseString(body).getAsJsonObject());
            sessionHeaders.add(exchange.getRequestHeaders().getFirst("mcp-session-id"));
            final String method = requests.get(requests.size() - 1).get("method").getAsString();
            switch (method) {
                case "initialize" -> sse(exchange, 200, """
                        event: message
                        data: {"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"1"}},"id":1,"jsonrpc":"2.0"}
                        """, "test-session");
                case "notifications/initialized" -> empty(exchange, 202);
                default -> sse(exchange, 200, """
                        event: message
                        data: {"result":{"content":[{"type":"text","text":"Grizzly Bears {1}{G} Creature - Bear 2/2"}],"isError":false},"id":2,"jsonrpc":"2.0"}
                        """, null);
            }
        });
        server.start();
    }

    @AfterMethod
    public void stopFakeMcpServer() {
        server.stop(0);
    }

    private static void sse(com.sun.net.httpserver.HttpExchange exchange, int status, String body, String session) throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        if (session != null) {
            exchange.getResponseHeaders().add("mcp-session-id", session);
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void empty(com.sun.net.httpserver.HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    @Test
    public void aLookupInitializesASessionAndCallsTheTool() {
        final McpCardLookup lookup = new McpCardLookup("http://127.0.0.1:" + server.getAddress().getPort() + "/");

        final String result = lookup.lookup("Grizzly Bears");

        assertTrue(result.contains("Grizzly Bears {1}{G}"));
        assertEquals(requests.size(), 3);
        assertEquals(requests.get(0).get("method").getAsString(), "initialize");
        assertEquals(requests.get(1).get("method").getAsString(), "notifications/initialized");
        final JsonObject call = requests.get(2);
        assertEquals(call.get("method").getAsString(), "tools/call");
        final JsonObject params = call.getAsJsonObject("params");
        assertEquals(params.get("name").getAsString(), "lookupCard");
        assertEquals(params.getAsJsonObject("arguments").get("cardName").getAsString(), "Grizzly Bears");
        assertEquals(sessionHeaders.get(2), "test-session", "the tool call carries the session id");
    }

    @Test
    public void aServerFailureComesBackAsReadableText() throws IOException {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> empty(exchange, 500));
        server.start();

        final String result = new McpCardLookup("http://127.0.0.1:" + server.getAddress().getPort() + "/").lookup("Anything");

        assertTrue(result.startsWith("Error: card lookup failed"), result);
    }
}
