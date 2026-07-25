package com.jdragon.studio.worker.web.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;

/** Bounds synchronous Worker data-plane calls without consuming Dispatch executor capacity. */
public class RuntimeInvocationConcurrencyFilter extends OncePerRequestFilter {

    private static final String RUNTIME_PATH = "/internal/runtime/**";
    private static final String FLINK_RUNTIME_PATH = "/api/flink/runtime/**";
    private static final String HEALTH_PATH = "/internal/runtime/health";

    private final ObjectMapper objectMapper;
    private final Semaphore permits;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public RuntimeInvocationConcurrencyFilter(ObjectMapper objectMapper,
                                              StudioPlatformProperties properties) {
        this.objectMapper = objectMapper;
        Integer configured = properties == null ? null : properties.getRuntimeInvocationMaxConcurrency();
        this.permits = new Semaphore(Math.max(1, configured == null ? 32 : configured.intValue()), true);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = requestPath(request);
        return HEALTH_PATH.equals(path) || (!pathMatcher.match(RUNTIME_PATH, path)
                && !pathMatcher.match(FLINK_RUNTIME_PATH, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!permits.tryAcquire()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(Result.error(
                    StudioErrorCode.SERVICE_UNAVAILABLE,
                    "Worker runtime invocation concurrency limit is reached")));
            return;
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }

    private String requestPath(HttpServletRequest request) {
        String path = request.getServletPath();
        if (StringUtils.hasText(path)) {
            return path;
        }
        path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && path.startsWith(contextPath)) {
            return path.substring(contextPath.length());
        }
        return path;
    }
}
