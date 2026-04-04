package com.jdragon.studio.worker.web.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
        return !pathMatcher.match("/internal/**", request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = request.getHeader(StudioConstants.INTERNAL_API_TOKEN_HEADER);
        String expected = properties.getInternalApiToken();
        if (expected == null || expected.trim().isEmpty() || !expected.equals(provided)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(
                    Result.error(StudioErrorCode.UNAUTHORIZED, "Invalid internal API token")));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
