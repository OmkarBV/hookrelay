package io.hookrelay.dispatcher.delivery;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;

/**
 * Wraps {@link HttpClient} on a virtual-thread-per-task executor. This
 * workload is entirely IO-bound and blocking-per-request (send, wait for the
 * receiver, read the response) — exactly the case virtual threads exist for.
 * A fixed platform-thread pool would force a hard choice between "small pool,
 * one slow receiver stalls everything behind it" and "huge pool, most
 * threads just burn ~1MB of stack sitting idle waiting on a socket." Virtual
 * threads make that trade-off disappear: each blocked call parks cheaply,
 * and the real concurrency ceiling is enforced deliberately (the semaphore
 * and bulkhead in DeliveryExecutionService), not accidentally by thread-pool
 * exhaustion.
 *
 * <p>One pinning pitfall worth being explicit about: a virtual thread stays
 * pinned to its carrier platform thread for the duration of any {@code
 * synchronized} block or method it executes, so blocking IO inside a {@code
 * synchronized} block defeats the whole point (the carrier can't be freed to
 * run other virtual threads while parked). Nothing on this delivery path
 * uses {@code synchronized} — the semaphore and Resilience4j bulkhead below
 * are both non-blocking-friendly (java.util.concurrent primitives, not
 * intrinsic locks) for exactly this reason.
 *
 * <p>{@code connectTimeout} is fixed at the HttpClient level (Java's API
 * doesn't expose it per-request); the per-endpoint {@code timeout_ms} from
 * configuration is applied per-request via {@link HttpRequest.Builder#timeout},
 * which bounds the entire exchange and so is the dominant, actually-configurable
 * limit in practice.
 */
@Component
public class HttpDeliveryClient {

    private static final int MAX_RESPONSE_BODY_BYTES = 4096;

    private final HttpClient httpClient;

    public HttpDeliveryClient() {
        ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(virtualThreadExecutor)
                .build();
    }

    public DeliveryHttpResult send(String url, Map<String, String> headers, String body, int timeoutMs) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(requestBuilder::header);

        long start = System.nanoTime();
        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            int latencyMs = elapsedMs(start);
            return DeliveryHttpResult.ofResponse(response.statusCode(), truncate(response.body()), latencyMs);
        } catch (HttpTimeoutException e) {
            return DeliveryHttpResult.ofNetworkError("TIMEOUT", elapsedMs(start));
        } catch (IOException e) {
            return DeliveryHttpResult.ofNetworkError("CONNECTION_ERROR", elapsedMs(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeliveryHttpResult.ofNetworkError("INTERRUPTED", elapsedMs(start));
        }
    }

    private int elapsedMs(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000);
    }

    private String truncate(String body) {
        if (body == null) {
            return null;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_RESPONSE_BODY_BYTES) {
            return body;
        }
        return new String(bytes, 0, MAX_RESPONSE_BODY_BYTES, StandardCharsets.UTF_8);
    }
}
