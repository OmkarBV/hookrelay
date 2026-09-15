# Receiving webhooks from Hookrelay

## Headers sent with every delivery

| Header | Meaning |
|---|---|
| `Hookrelay-Id` | The delivery's id. Stable across retries of the same delivery — use it to deduplicate (see below). |
| `Hookrelay-Timestamp` | Unix timestamp (seconds) when this attempt was signed. |
| `Hookrelay-Signature` | `t=<timestamp>,v1=<hex hmac>[,v1=<hex hmac>...]` — see verification below. |
| `Content-Type` | Always `application/json`. |

The request body is the exact JSON payload you sent to `POST /api/v1/events`, byte-for-byte.

## Verifying a signature

The signed string is `<timestamp>.<raw request body>` — the timestamp from
`Hookrelay-Timestamp` (or the `t=` value in the signature header, they're
the same), a literal `.`, then the raw body **exactly as received**. Don't
re-serialize a parsed JSON object before verifying — even semantically
identical JSON can produce different bytes (key order, whitespace), which
will not match the signature. Read the raw body first, verify, then parse.

Compute `HMAC-SHA256(signed_string, your_secret)`, hex-encode it, and check
it matches one of the `v1=` values in the header using a constant-time
comparison — never `==` or `.equals()`, which can leak timing information
about how many leading bytes matched.

There can be more than one `v1=` value. This happens during secret rotation:
Hookrelay signs with every non-retired secret you have, so you can start
verifying with a new secret before the old one is retired, with no gap where
deliveries would fail verification. Accept if **any** `v1=` value matches.

Reject the request if the timestamp is too far in the past (we recommend 5
minutes) — this is what stops a captured, valid request from being replayed
later.

### Java

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

public boolean verify(String rawBody, String signatureHeader, String secret) throws Exception {
    // signatureHeader looks like: "t=1699999999,v1=abcd...,v1=ef01..."
    String[] parts = signatureHeader.split(",");
    long timestamp = Long.parseLong(parts[0].substring("t=".length()));

    if (Math.abs(Instant.now().getEpochSecond() - timestamp) > 300) {
        return false; // too old (or too far in the future) — possible replay
    }

    String signedPayload = timestamp + "." + rawBody;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] expected = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
    String expectedHex = HexFormat.of().formatHex(expected);

    for (int i = 1; i < parts.length; i++) {
        String candidate = parts[i].substring("v1=".length());
        if (MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                expectedHex.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
    }
    return false;
}
```

### Node.js

```javascript
const crypto = require('crypto');

function verify(rawBody, signatureHeader, secret) {
  const parts = signatureHeader.split(',');
  const timestamp = parseInt(parts[0].slice('t='.length), 10);

  if (Math.abs(Math.floor(Date.now() / 1000) - timestamp) > 300) {
    return false; // too old (or too far in the future) — possible replay
  }

  const signedPayload = `${timestamp}.${rawBody}`;
  const expected = crypto
    .createHmac('sha256', secret)
    .update(signedPayload, 'utf8')
    .digest('hex');
  const expectedBuf = Buffer.from(expected, 'utf8');

  return parts.slice(1).some((part) => {
    const candidate = Buffer.from(part.slice('v1='.length), 'utf8');
    return candidate.length === expectedBuf.length
        && crypto.timingSafeEqual(candidate, expectedBuf);
  });
}

// Express: read the raw body before any JSON-parsing middleware touches it.
app.post('/webhooks/hookrelay', express.raw({ type: 'application/json' }), (req, res) => {
  const rawBody = req.body.toString('utf8');
  if (!verify(rawBody, req.header('Hookrelay-Signature'), process.env.HOOKRELAY_ENDPOINT_SECRET)) {
    return res.status(401).send('invalid signature');
  }
  const event = JSON.parse(rawBody);
  // ... handle event ...
  res.status(200).end();
});
```

## Delivery is at-least-once — your endpoint must be idempotent

Hookrelay guarantees a delivery is attempted at least once, not exactly
once. Network partitions, timeouts on responses that actually succeeded
server-side, and retries all mean the same event can legitimately arrive at
your endpoint more than once.

Use `Hookrelay-Id` to deduplicate: store the ids you've already processed
(even just the last N minutes' worth in a cache is usually enough) and skip
any repeat before applying side effects. Two different attempts *of the same
delivery* carry the same `Hookrelay-Id`; a genuinely new event gets a new
one.

## What counts as success

Any `2xx` response marks the delivery successful. Anything else — including
no response at all (timeout, connection refused) — is treated as a failure
and retried on a backoff schedule, except:

- **`429`** is retried (it's an explicit "slow down," not a rejection)
- **Any other `4xx`** is treated as terminal and is **not** retried — we
  assume you looked at the request and rejected it on purpose, and retrying
  the identical request would just get the identical rejection. If you fix
  whatever caused the rejection, use the replay API to redeliver it rather
  than waiting for an automatic retry that will never come.

Respond quickly — Hookrelay applies a per-endpoint timeout (10s by default)
and treats a response that arrives after that as a timeout, even if your
server was about to send a 200. Do your real work asynchronously after
responding if it takes any real time.
