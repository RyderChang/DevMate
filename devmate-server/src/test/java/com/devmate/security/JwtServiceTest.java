package com.devmate.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {
    private static final String SECRET = "unit-test-jwt-secret-that-is-at-least-32-bytes-long";
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void generatesAndParsesSignedIdentity() {
        JwtService service = serviceAt(now);
        String token = service.generate(new CurrentUser(42L, "alice", List.of("USER", "ADMIN", "USER")));
        assertThat(service.parse(token)).isEqualTo(new CurrentUser(42L, "alice", List.of("ADMIN", "USER")));
        var claims = io.jsonwebtoken.Jwts.parser().verifyWith(
                        io.jsonwebtoken.security.Keys.hmacShaKeyFor(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .clock(() -> java.util.Date.from(now))
                .build().parseSignedClaims(token).getPayload();
        assertThat(claims.get("id", Number.class).longValue()).isEqualTo(42L);
        assertThat(claims.get("roles", List.class)).containsExactly("ADMIN", "USER");
        assertThat(claims).containsKeys("iat", "exp").doesNotContainKeys("role", "permissions");
    }

    @Test
    void rejectsExpiredAndTamperedTokens() {
        String token = serviceAt(now).generate(new CurrentUser(42L, "alice", List.of("USER")));
        assertThatThrownBy(() -> serviceAt(now.plusSeconds(61)).parse(token)).isInstanceOf(ExpiredJwtException.class);
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");
        assertThatThrownBy(() -> serviceAt(now).parse(tampered)).isInstanceOf(JwtException.class);
    }

    private JwtService serviceAt(Instant instant) {
        return new JwtService(new JwtProperties(SECRET, Duration.ofMinutes(1)), Clock.fixed(instant, ZoneOffset.UTC));
    }
}
