package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtTokenService {

    private final SecretKey secretKey;
    private final long tokenExpirationSeconds;

    public JwtTokenService(StudioPlatformProperties properties) {
        byte[] secret = (properties.getEncryptionSecret() + "-jwt-sign-key-2026")
                .getBytes(StandardCharsets.UTF_8);
        this.secretKey = Keys.hmacShaKeyFor(secret.length >= 32 ? secret : pad(secret));
        Long configuredExpiration = properties.getAuth().getTokenExpirationSeconds();
        this.tokenExpirationSeconds = configuredExpiration == null || configuredExpiration.longValue() <= 0L
                ? 12L * 3600L
                : configuredExpiration.longValue();
    }

    public String createToken(String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(tokenExpirationSeconds)))
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public long getTokenExpirationSeconds() {
        return tokenExpirationSeconds;
    }

    public String parseUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(secretKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private byte[] pad(byte[] bytes) {
        byte[] padded = new byte[32];
        for (int i = 0; i < padded.length; i++) {
            padded[i] = i < bytes.length ? bytes[i] : (byte) '0';
        }
        return padded;
    }
}
