package com.jdragon.studio.server.web.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.infra.security.StudioTokenResolver.ResolvedStudioToken;
import com.jdragon.studio.infra.security.StudioTokenResolver.StudioTokenSource;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.server.web.security.StudioHttpTokenResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

@Component
public class StudioCookieCsrfFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final StudioHttpTokenResolver tokenResolver;
    private final ObjectMapper objectMapper;
    private final StudioPlatformProperties.AuthProperties authProperties;

    public StudioCookieCsrfFilter(StudioHttpTokenResolver tokenResolver,
                                  ObjectMapper objectMapper,
                                  StudioPlatformProperties platformProperties) {
        this.tokenResolver = tokenResolver;
        this.objectMapper = objectMapper;
        this.authProperties = platformProperties.getAuth();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!authProperties.isCookieCsrfEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }
        ResolvedStudioToken resolved = tokenResolver.resolve(request);
        if (resolved != null
                && resolved.getSource() == StudioTokenSource.STUDIO_COOKIE
                && !hasExplicitCredential(request)
                && isUnsafeMethod(request.getMethod())
                && !isTrustedBrowserRequest(request)) {
            writeForbidden(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * A request that already carries an explicit non-cookie credential is not an ambient-cookie
     * CSRF vector: a cross-site attacker cannot attach the X-Studio-Token header or an
     * Authorization bearer token without a CORS preflight. Such requests keep the cookie-based
     * flow usable in cross-origin development topologies (e.g. Vite dev server proxying to the
     * backend) while cookie-only cross-site writes remain blocked.
     */
    private boolean hasExplicitCredential(HttpServletRequest request) {
        if (hasText(request.getHeader(StudioConstants.STUDIO_TOKEN_HEADER))) {
            return true;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null) {
            String trimmed = authorization.trim();
            return trimmed.length() > BEARER_PREFIX.length() && trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());
        }
        return false;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean isUnsafeMethod(String method) {
        return !(HttpMethod.GET.matches(method)
                || HttpMethod.HEAD.matches(method)
                || HttpMethod.OPTIONS.matches(method)
                || HttpMethod.TRACE.matches(method));
    }

    private boolean isTrustedBrowserRequest(HttpServletRequest request) {
        String fetchSite = trimToNull(request.getHeader("Sec-Fetch-Site"));
        if (fetchSite != null
                && !"same-origin".equalsIgnoreCase(fetchSite)
                && !"none".equalsIgnoreCase(fetchSite)) {
            return false;
        }

        String origin = trimToNull(request.getHeader(HttpHeaders.ORIGIN));
        return origin == null || sameOrigin(origin, resolveRequestOrigin(request));
    }

    private String resolveRequestOrigin(HttpServletRequest request) {
        String scheme = firstHeaderValue(request.getHeader("X-Forwarded-Proto"));
        if (scheme == null) {
            scheme = request.getScheme();
        }
        String host = firstHeaderValue(request.getHeader("X-Forwarded-Host"));
        if (host == null) {
            host = request.getHeader(HttpHeaders.HOST);
        }
        if (host == null || host.trim().isEmpty()) {
            host = request.getServerName() + effectivePortSuffix(scheme, request.getServerPort());
        }
        return scheme + "://" + host;
    }

    private boolean sameOrigin(String left, String right) {
        try {
            URI leftUri = URI.create(left);
            URI rightUri = URI.create(right);
            return equalsIgnoreCase(leftUri.getScheme(), rightUri.getScheme())
                    && equalsIgnoreCase(leftUri.getHost(), rightUri.getHost())
                    && effectivePort(leftUri) == effectivePort(rightUri);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private String effectivePortSuffix(String scheme, int port) {
        boolean defaultPort = ("https".equalsIgnoreCase(scheme) && port == 443)
                || ("http".equalsIgnoreCase(scheme) && port == 80);
        return defaultPort ? "" : ":" + port;
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private String firstHeaderValue(String value) {
        if (value == null) {
            return null;
        }
        int commaIndex = value.indexOf(',');
        return trimToNull(commaIndex < 0 ? value : value.substring(0, commaIndex));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                Result.error(StudioErrorCode.FORBIDDEN, "Cross-origin cookie-authenticated request is not allowed")));
    }
}
