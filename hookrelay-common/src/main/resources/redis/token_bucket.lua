-- Atomic token-bucket check-and-consume. Everything happens in one script
-- so "read current tokens, compute refill, decide, write back" can't race
-- across concurrent callers hitting the same key from different app
-- instances — the classic reason a naive read-then-write rate limiter in
-- application code leaks extra requests under concurrency.
--
-- KEYS[1]            bucket key
-- ARGV[1] capacity    max tokens the bucket can hold
-- ARGV[2] refillRate  tokens added per second
-- ARGV[3] now         current time in epoch milliseconds
-- ARGV[4] requested   tokens this call wants to consume (usually 1)
--
-- returns { allowed (1/0), tokensRemaining (string, for float precision —
--           Redis's Lua-to-RESP conversion truncates numbers to integers),
--           retryAfterMillis (0 if allowed) }

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

local bucket = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local ts = tonumber(bucket[2])

if tokens == nil then
    tokens = capacity
    ts = now
end

local elapsed_ms = math.max(0, now - ts)
tokens = math.min(capacity, tokens + (elapsed_ms * refill_rate / 1000.0))

local allowed = 0
local retry_after_ms = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
else
    local deficit = requested - tokens
    retry_after_ms = math.ceil(deficit / refill_rate * 1000.0)
end

redis.call('HMSET', key, 'tokens', tostring(tokens), 'ts', now)
-- Idle buckets expire rather than accumulating forever for
-- applications/endpoints that stop sending traffic.
redis.call('PEXPIRE', key, 60000)

return {allowed, tostring(tokens), retry_after_ms}
