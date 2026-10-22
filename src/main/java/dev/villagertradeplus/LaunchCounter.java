package dev.villagertradeplus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

/** One best-effort request per successful enable, with no server or player identifiers. */
final class LaunchCounter {
    private static final URI ENDPOINT = URI.create("https://count.getloli.com/@TradePlus-launch");
    private final URI endpoint;

    LaunchCounter() { this(ENDPOINT); }
    LaunchCounter(URI endpoint) { this.endpoint = endpoint; }

    void recordIfEnabled(boolean enabled, Consumer<Runnable> dispatch, Consumer<String> failure) {
        if (!enabled) return;
        dispatch.accept(() -> {
            try { request(); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                failure.accept("Launch counter interrupted; skipped without retry.");
            } catch (IOException | RuntimeException error) {
                failure.accept("Launch counter unavailable; skipped without retry (" + error.getClass().getSimpleName() + ").");
            }
        });
    }

    private void request() throws IOException, InterruptedException {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                    .header("User-Agent", "VillagerTradePlus").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            // The counter is updated by the GET; the image itself is not needed.
            try (var ignored = response.body()) {
                if (response.statusCode() != 200) throw new IOException("Counter returned HTTP " + response.statusCode());
            }
        }
    }
}
