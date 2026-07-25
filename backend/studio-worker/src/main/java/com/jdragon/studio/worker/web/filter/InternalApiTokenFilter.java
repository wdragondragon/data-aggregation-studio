package com.jdragon.studio.worker.web.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.infra.service.RuntimeInternalHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class InternalApiTokenFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;
    private final StudioPlatformProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public InternalApiTokenFilter(ObjectMapper objectMapper, StudioPlatformProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (!StringUtils.hasText(path)) {
            path = request.getRequestURI();
            String contextPath = request.getContextPath();
            if (StringUtils.hasText(contextPath) && path.startsWith(contextPath)) {
                path = path.substring(contextPath.length());
            }
        }
        return !pathMatcher.match("/internal/**", path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        StudioRequestContextHolder.clear();
        try {
            String provided = request.getHeader(StudioConstants.INTERNAL_API_TOKEN_HEADER);
            String expected = properties.getInternalApiToken();
            if (!tokenMatches(expected, provided)) {
                response.setHeader(RuntimeInternalHeaders.INTERNAL_ERROR_HEADER,
                        RuntimeInternalHeaders.INTERNAL_AUTHENTICATION);
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write(objectMapper.writeValueAsString(
                        Result.error(StudioErrorCode.UNAUTHORIZED, "Invalid internal API token")));
                return;
            }
            response.setHeader(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER,
                    RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED);
            filterChain.doFilter(request, response);
        } finally {
            StudioRequestContextHolder.clear();
        }
    }

    private boolean tokenMatches(String expected, String provided) {
        return expected != null && !expected.trim().isEmpty() && provided != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        provided.getBytes(StandardCharsets.UTF_8));
    }
}

