package com.devmate.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class JwtService {
    private final JwtProperties properties;
    private final Clock clock;

    @Autowired
public JwtService(JwtProperties properties) {
        this(properties, Clock.systemUTC());
    }

    JwtService(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String generate(CurrentUser user) {
        Instant issuedAt = clock.instant();
        return Jwts.builder()
                .subject(user.username())
                .claim("id", user.id())
                .claim("roles", user.roles())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(properties.expiration())))
                .signWith(signingKey())
                .compact();
    }

    public CurrentUser parse(String token) {
        Claims claims = Jwts.parser().verifyWith(signingKey()).clock(() -> Date.from(clock.instant()))
                .build().parseSignedClaims(token).getPayload();
        Number id = claims.get("id", Number.class);
        List<?> values = claims.get("roles", List.class);
        if (id == null || values == null || values.stream().anyMatch(value -> !(value instanceof String))) {
            throw new IllegalArgumentException("JWT identity claims are invalid");
        }
        List<String> roles = values.stream().map(String.class::cast).distinct().sorted().toList();
        return new CurrentUser(id.longValue(), claims.getSubject(), roles);
    }

    public long expirationSeconds() {
        return properties.expiration().toSeconds();
    }

    private SecretKey signingKey() {
        if (properties.secret() == null || properties.secret().isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be configured");
        }
        return Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }
}
