package com.jdragon.studio.server.web.security;

import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.JwtTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

@Component
public class StudioAuthCookieService {

    private final StudioPlatformProperties.AuthProperties authProperties;
    private final JwtTokenService jwtTokenService;

    public StudioAuthCookieService(StudioPlatformProperties platformProperties,
                                   JwtTokenService jwtTokenService) {
        this.authProperties = platformProperties.getAuth();
        this.jwtTokenService = jwtTokenService;
    }

    public void writeTokenCookie(HttpServletRequest request,
                                 HttpServletResponse response,
                                 String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, baseCookie(request, token)
                .maxAge(Duration.ofSeconds(jwtTokenService.getTokenExpirationSeconds()))
                .build()
                .toString());
    }

    public void clearTokenCookie(HttpServletRequest request,
                                 HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, baseCookie(request, "")
                .maxAge(Duration.ZERO)
                .build()
                .toString());
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(HttpServletRequest request, String value) {
        return ResponseCookie.from(StudioConstants.STUDIO_TOKEN_COOKIE, value)
                .httpOnly(true)
                .secure(resolveSecure(request))
                .sameSite(resolveSameSite())
                .path(resolvePath());
    }

    private boolean resolveSecure(HttpServletRequest request) {
        if (authProperties.isCookieSecure() || request.isSecure()) {
            return true;
        }
        String forwardedProto = firstHeaderValue(request.getHeader("X-Forwarded-Proto"));
        return "https".equalsIgnoreCase(forwardedProto);
    }

    private String resolvePath() {
        String configured = authProperties.getCookiePath();
        if (configured == null || configured.trim().isEmpty()) {
            return "/dfs/data-aggregation-studio";
        }
        String path = configured.trim();
        return path.startsWith("/") ? path : "/" + path;
    }

    private String resolveSameSite() {
        String configured = authProperties.getCookieSameSite();
        if (configured == null) {
            return "Lax";
        }
        String normalized = configured.trim().toLowerCase(Locale.ROOT);
        if ("strict".equals(normalized)) {
            return "Strict";
        }
        if ("none".equals(normalized)) {
            return "None";
        }
        return "Lax";
    }

    private String firstHeaderValue(String value) {
        if (value == null) {
            return null;
        }
        int commaIndex = value.indexOf(',');
        return (commaIndex < 0 ? value : value.substring(0, commaIndex)).trim();
    }
}
