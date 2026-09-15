package io.hookrelay.common.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Blocks endpoint URLs that resolve to a private, loopback, link-local, or
 * otherwise internal address — the classic SSRF vector where a "customer
 * webhook URL" is actually pointed at the delivering server's own internal
 * network or cloud metadata service.
 *
 * <p>Called from two places for two different reasons: EndpointService (at
 * registration, for fast feedback to whoever is configuring the endpoint)
 * and the dispatcher (at delivery time, which is the check that actually
 * matters — DNS can be re-pointed to an internal address at any point after
 * an endpoint passed validation once, so registration-time validation alone
 * is not a security control, only a convenience).
 *
 * <p>A Spring-managed component rather than a static utility specifically so
 * {@code hookrelay.security.ssrf-protection.enabled} can be overridden —
 * defaulting to {@code true} everywhere real traffic flows, and flipped to
 * {@code false} only in integration tests that legitimately need to target
 * an in-process mock server (WireMock, Testcontainers), which is otherwise
 * indistinguishable from the loopback/private addresses this class exists
 * to block. Production code and configuration never sets it to false.
 */
@Component
public class EndpointUrlValidator {

    private final boolean enforced;

    public EndpointUrlValidator(@Value("${hookrelay.security.ssrf-protection.enabled:true}") boolean enforced) {
        this.enforced = enforced;
    }

    /**
     * @throws SsrfViolationException if the URL is malformed, uses a
     *     non-HTTP(S) scheme, or resolves to a blocked address
     * @throws UnknownHostException if the host cannot be resolved at all —
     *     deliberately not wrapped as an SsrfViolationException, since
     *     callers need to tell "this is dangerous" apart from "this is
     *     currently unreachable" (the latter is a transient, retryable
     *     condition at delivery time, not a security block)
     */
    public void validate(String rawUrl) throws UnknownHostException {
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw new SsrfViolationException("Malformed endpoint URL: " + rawUrl);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new SsrfViolationException("Endpoint URL must use http or https: " + rawUrl);
        }
        String host = uri.getHost();
        if (host == null) {
            throw new SsrfViolationException("Endpoint URL has no host: " + rawUrl);
        }
        if (!enforced) {
            return;
        }

        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (isBlocked(address)) {
                throw new SsrfViolationException(
                        "Endpoint URL resolves to a blocked address (%s -> %s)".formatted(host, address.getHostAddress()));
            }
        }
    }

    private static boolean isBlocked(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || address.isAnyLocalAddress()
                // Cloud metadata endpoints (AWS/GCP/Azure). Already covered
                // by isLinkLocalAddress() for 169.254.0.0/16, but this is
                // security-critical enough to check explicitly rather than
                // trust a single general-purpose method.
                || "169.254.169.254".equals(address.getHostAddress());
    }
}
