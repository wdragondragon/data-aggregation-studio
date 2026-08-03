package com.jdragon.studio.worker.web.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.flink.connector.AggregationFlinkRuntimeRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Validates the short-lived Flink connector capability before request-body deserialization. */
public class FlinkRuntimeCapabilityFilter extends OncePerRequestFilter {

    private static final String RESOLVE_PATH = "/api/flink/runtime/resolve";
    private static final String AUDIT_PATH = "/api/flink/runtime/audit";
    private static final String ARTIFACT_PATH = "/api/flink/runtime/plugin/artifact";

    private final ObjectMapper objectMapper;

    public FlinkRuntimeCapabilityFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
        return !RESOLVE_PATH.equals(path) && !AUDIT_PATH.equals(path) && !ARTIFACT_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = request.getHeader(AggregationFlinkRuntimeRegistry.CAPABILITY_TOKEN_HEADER);
        if (!AggregationFlinkRuntimeRegistry.isValid(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(Result.error(
                    StudioErrorCode.UNAUTHORIZED, "Invalid or expired Flink runtime capability")));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
