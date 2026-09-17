package io.hookrelay.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisTokenBucketTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static RedisTokenBucket bucket;

    @BeforeAll
    static void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();
        bucket = new RedisTokenBucket(redisTemplate);
    }

    private String freshKey() {
        return "test:" + UUID.randomUUID();
    }

    @Test
    void allowsRequestsUpToCapacityThenDenies() {
        String key = freshKey();
        for (int i = 0; i < 5; i++) {
            TokenBucketResult result = bucket.tryConsume(key, 5, 1, 1);
            assertThat(result.allowed()).as("request %d of 5 within capacity", i + 1).isTrue();
        }
        TokenBucketResult sixth = bucket.tryConsume(key, 5, 1, 1);
        assertThat(sixth.allowed()).isFalse();
        assertThat(sixth.retryAfter()).isPositive();
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        String key = freshKey();
        // Capacity 2, refill 10/s: exhaust the burst, then wait past one
        // token's refill interval (100ms) and confirm it's available again.
        assertThat(bucket.tryConsume(key, 2, 10, 1).allowed()).isTrue();
        assertThat(bucket.tryConsume(key, 2, 10, 1).allowed()).isTrue();
        assertThat(bucket.tryConsume(key, 2, 10, 1).allowed()).isFalse();

        Thread.sleep(150);

        assertThat(bucket.tryConsume(key, 2, 10, 1).allowed()).isTrue();
    }

    @Test
    void retryAfterReflectsHowLongUntilARequestWouldSucceed() throws InterruptedException {
        String key = freshKey();
        assertThat(bucket.tryConsume(key, 1, 2, 1).allowed()).isTrue(); // capacity 1, refill 2/s
        TokenBucketResult denied = bucket.tryConsume(key, 1, 2, 1);
        assertThat(denied.allowed()).isFalse();
        // Needs 1 token at 2/s refill => ~500ms
        assertThat(denied.retryAfter()).isBetween(Duration.ofMillis(400), Duration.ofMillis(600));

        Thread.sleep(denied.retryAfter().toMillis() + 50);
        assertThat(bucket.tryConsume(key, 1, 2, 1).allowed()).isTrue();
    }

    @Test
    void isAtomicUnderConcurrentAccess() throws InterruptedException {
        String key = freshKey();
        int capacity = 50;
        int attempts = 200;
        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch done = new CountDownLatch(attempts);
        AtomicInteger allowedCount = new AtomicInteger();

        for (int i = 0; i < attempts; i++) {
            executor.submit(() -> {
                try {
                    // Very slow refill so it doesn't meaningfully add tokens
                    // during the test — isolates the "exactly capacity
                    // allowed" concurrency guarantee from refill timing.
                    if (bucket.tryConsume(key, capacity, 0.001, 1).allowed()) {
                        allowedCount.incrementAndGet();
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(allowedCount.get()).isEqualTo(capacity);
    }
}
