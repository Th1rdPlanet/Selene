package ru.moonlightproject.dev.selene.velocity;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Minimal Prometheus text endpoint on /metrics using the JDK HTTP server. */
final class PrometheusExporter implements AutoCloseable {
    private final HttpServer server;

    PrometheusExporter(String bind, Consumer<StringBuilder> render) throws IOException {
        int colon = bind.lastIndexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("telemetry-prometheus-bind must be host:port");
        String host = bind.substring(0, colon).replace("[", "").replace("]", "");
        server = HttpServer.create(new InetSocketAddress(host, Integer.parseInt(bind.substring(colon + 1))), 16);
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "selene-prometheus");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/metrics", exchange -> {
            StringBuilder out = new StringBuilder(4096);
            render.accept(out);
            byte[] body = out.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(body);
            }
        });
        server.start();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
