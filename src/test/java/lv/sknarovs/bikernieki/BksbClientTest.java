package lv.sknarovs.bikernieki;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.YearMonth;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Runs against a local HTTP server; never touches the network. */
class BksbClientTest {
    HttpServer server;
    ExecutorService handlers;
    final AtomicInteger requests = new AtomicInteger();

    BksbClient clientFor(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { requests.incrementAndGet(); handler.handle(exchange); });
        handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.start();
        return new BksbClient("http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofMillis(300), Duration.ofMillis(800), Duration.ZERO);
    }

    static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @AfterEach void stop() {
        if (server != null) {
            server.stop(0);
            handlers.shutdownNow();
        }
    }

    @Test void monthUrlUsesTwoDigitMonth() {
        assertEquals("https://bksb.lv/index.php/2014-01-03-13-49-44/month.calendar/2026/03/01/-",
                BksbClient.monthUrl("https://bksb.lv", YearMonth.of(2026, 3)));
    }

    @Test void fetchesMonthPageWithBrowserUserAgent() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> agent = new AtomicReference<>();
        BksbClient client = clientFor(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            agent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            respond(exchange, 200, "<html>Biķernieki</html>");
        });
        assertEquals(Optional.of("<html>Biķernieki</html>"), client.fetch(YearMonth.of(2026, 10)));
        assertEquals("/index.php/2014-01-03-13-49-44/month.calendar/2026/10/01/-", path.get());
        assertEquals(BksbClient.USER_AGENT, agent.get());
    }

    @Test void retriesOnceAfterServerError() throws IOException {
        BksbClient client = clientFor(exchange -> respond(exchange, requests.get() == 1 ? 500 : 200, "ok"));
        assertEquals(Optional.of("ok"), client.fetch(YearMonth.of(2026, 10)));
        assertEquals(2, requests.get());
    }

    @Test void givesUpAfterTwoFailedAttempts() throws IOException {
        BksbClient client = clientFor(exchange -> respond(exchange, 503, "down"));
        assertEquals(Optional.empty(), client.fetch(YearMonth.of(2026, 10)));
        assertEquals(2, requests.get());
    }

    @Test void slowServerTimesOut() throws IOException {
        BksbClient client = clientFor(exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "late");
        });
        assertTimeout(Duration.ofSeconds(3),
                () -> assertEquals(Optional.empty(), client.fetch(YearMonth.of(2026, 10))));
    }
}
