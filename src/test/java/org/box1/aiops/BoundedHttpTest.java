package org.box1.aiops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** Review S-1, S-7: a stalled body hits the overall deadline; a large body stops at the cap. */
class BoundedHttpTest {
    HttpServer server;
    final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stall", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("{\"partial\":".getBytes());
            out.flush();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            out.close();
        });
        server.createContext("/big", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 100; i++) {
                    out.write(new byte[1024]);
                }
            } catch (IOException ignored) {
                // the client stopped reading
            }
        });
        server.createContext("/ok", exchange -> {
            byte[] b = "{\"a\":1}".getBytes();
            exchange.sendResponseHeaders(200, b.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(b);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
            .timeout(Duration.ofSeconds(10)).GET().build();
    }

    @Test
    void aStalledBodyTimesOutAtTheOverallDeadline() {
        long start = System.nanoTime();
        assertThrows(BoundedHttp.Timeout.class,
            () -> BoundedHttp.send(client, get("/stall"), 1024, Duration.ofMillis(400)));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3000);
    }

    @Test
    void aLargeBodyStopsAtTheCap() throws Exception {
        BoundedHttp.Result r = BoundedHttp.send(client, get("/big"), 4096, Duration.ofSeconds(5));
        assertTrue(r.tooLarge());
    }

    @Test
    void aNormalBodyIsReturned() throws Exception {
        BoundedHttp.Result r = BoundedHttp.send(client, get("/ok"), 4096, Duration.ofSeconds(5));
        assertEquals(200, r.status());
        assertEquals("{\"a\":1}", new String(r.body()));
    }
}
