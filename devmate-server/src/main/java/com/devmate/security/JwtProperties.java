package com.devmate.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "devmate.jwt")
public record JwtProperties(String secret, Duration expiration) {
}
