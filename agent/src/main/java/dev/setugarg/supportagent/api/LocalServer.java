package dev.setugarg.supportagent.api;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Run the exact same app on your laptop: {@code java -jar agent/target/agent-lambda.jar}
 * then open http://localhost:8080. Uses in-memory stores unless {@code STORE=dynamo}.
 */
public final class LocalServer {

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(App.env("PORT", "8080"));
        ChatService service = new ChatService(App.fromEnv());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ex -> {
            Map<String, String> headers = new HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.isEmpty() ? "" : v.getFirst()));
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            ChatService.HttpResult r = service.handle(ex.getRequestMethod(), ex.getRequestURI().getPath(), headers, body);
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", r.contentType());
            ex.sendResponseHeaders(r.status(), bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        System.out.println("Support agent running at http://localhost:" + port + "  (routes: " + ChatService.routes() + ")");
    }
}
