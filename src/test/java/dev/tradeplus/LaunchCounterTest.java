package dev.tradeplus;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LaunchCounterTest {
    private HttpServer server;
    private URI endpoint;
    private final List<Runnable> tasks = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/counter?theme=test");
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void disabledDoesNotScheduleOrContactService() {
        new LaunchCounter(endpoint).recordIfEnabled(false, tasks::add, failures::add);
        assertTrue(tasks.isEmpty());
        assertTrue(failures.isEmpty());
    }

    @Test void schedulesOneGetWithoutSendingServerOrDeviceDetails() {
        var hits = new AtomicInteger();
        var observed = new ArrayList<String>();
        server.createContext("/counter", exchange -> {
            hits.incrementAndGet();
            observed.add(exchange.getRequestMethod());
            observed.add(exchange.getRequestURI().toString());
            observed.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            observed.add(exchange.getRequestHeaders().getFirst("Cookie"));
            observed.add(exchange.getRequestHeaders().getFirst("Authorization"));
            observed.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        new LaunchCounter(endpoint).recordIfEnabled(true, tasks::add, failures::add);
        assertEquals(0, hits.get(), "Network work must be dispatched off the caller thread");
        assertEquals(1, tasks.size());
        tasks.getFirst().run();
        assertEquals(1, hits.get());
        assertEquals(java.util.Arrays.asList("GET", "/counter?theme=test", "TradePlus", null, null, ""), observed);
        assertTrue(failures.isEmpty());
    }

    @Test void serviceFailureDoesNotThrowOrRetry() {
        var hits = new AtomicInteger();
        server.createContext("/counter", exchange -> {
            hits.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close();
        });
        new LaunchCounter(endpoint).recordIfEnabled(true, tasks::add, failures::add);
        assertDoesNotThrow(() -> tasks.getFirst().run());
        assertEquals(1, hits.get());
        assertEquals(1, failures.size());
    }

    @Test void redirectDoesNotContactAnotherEndpoint() {
        var redirected = new AtomicInteger();
        server.createContext("/counter", exchange -> {
            exchange.getResponseHeaders().set("Location", "/unexpected");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/unexpected", exchange -> {
            redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        new LaunchCounter(endpoint).recordIfEnabled(true, tasks::add, failures::add);
        tasks.getFirst().run();
        assertEquals(0, redirected.get());
        assertEquals(1, failures.size());
    }
}
