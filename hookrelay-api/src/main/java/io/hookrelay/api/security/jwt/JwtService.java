package io.hookrelay.api.security.jwt;

import io.hookrelay.common.admin.AdminRole;
import io.hookrelay.common.admin.AdminUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtService {

    private static final String CLAIM_TENANT_ID = "tenantId";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey signingKey;
    private final Duration tokenTtl;

    public JwtService(
            @Value("${hookrelay.security.jwt.secret}") String secret,
            @Value("${hookrelay.security.jwt.ttl-seconds:3600}") long tokenTtlSeconds) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.tokenTtl = Duration.ofSeconds(tokenTtlSeconds);
    }

    public String issue(AdminUser adminUser) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(adminUser.getId().toString())
                .claim(CLAIM_TENANT_ID, adminUser.getTenant().getId().toString())
                .claim(CLAIM_ROLE, adminUser.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(tokenTtl)))
                .signWith(signingKey)
                .compact();
    }

    public Optional<AdminPrincipal> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(new AdminPrincipal(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get(CLAIM_TENANT_ID, String.class)),
                    AdminRole.valueOf(claims.get(CLAIM_ROLE, String.class))));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
