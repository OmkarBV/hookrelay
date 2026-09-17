package io.hookrelay.common.ratelimit;

import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

/**
 * A Redis-backed token bucket shared by every application instance —
 * ingestion rate limiting (hookrelay-api, per application) and outbound
 * delivery rate limiting (hookrelay-dispatcher, per endpoint) both need the
 * limit to hold across however many instances of each service are running,
 * which an in-memory bucket (e.g. Resilience4j's RateLimiter, used
 * elsewhere in this codebase for things that are deliberately
 * per-instance) can't provide.
 *
 * <p>The check-and-consume happens in one Lua script (see
 * redis/token_bucket.lua) so it's atomic — without that, two concurrent
 * requests against the same key could both read "1 token left," both decide
 * to allow, and both consume it, letting the bucket leak past its limit
 * under load.
 */
@Component
public class RedisTokenBucket {

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> script;

    public RedisTokenBucket(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setResultType(List.class);
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/token_bucket.lua")));
        this.script = script;
    }

    /**
     * @param key unique bucket identity, e.g. {@code "ratelimit:ingest:<applicationId>"}
     * @param capacity maximum tokens the bucket holds (the burst allowance)
     * @param refillPerSecond tokens added back per second (the sustained rate)
     */
    @SuppressWarnings("unchecked")
    public TokenBucketResult tryConsume(String key, double capacity, double refillPerSecond, int requested) {
        List<Object> result = redisTemplate.execute(
                script,
                List.of(key),
                String.valueOf(capacity),
                String.valueOf(refillPerSecond),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(requested));

        boolean allowed = ((Long) result.get(0)) == 1L;
        double tokensRemaining = Double.parseDouble((String) result.get(1));
        long retryAfterMillis = (Long) result.get(2);
        return new TokenBucketResult(allowed, tokensRemaining, Duration.ofMillis(retryAfterMillis));
    }
}
