package com.jdragon.studio.flink.web.filter;

import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.infra.security.StudioUserPrincipal;
import com.jdragon.studio.infra.service.StudioAccessService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class FlinkRequestContextFilter extends OncePerRequestFilter {

    private final StudioAccessService studioAccessService;

    public FlinkRequestContextFilter(StudioAccessService studioAccessService) {
        this.studioAccessService = studioAccessService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof StudioUserPrincipal) {
                StudioUserPrincipal principal = (StudioUserPrincipal) authentication.getPrincipal();
                StudioRequestContextHolder.setContext(studioAccessService.buildRequestContext(
                        principal,
                        request.getHeader(StudioConstants.REQUEST_TENANT_HEADER),
                        request.getHeader(StudioConstants.REQUEST_PROJECT_HEADER)));
            }
            filterChain.doFilter(request, response);
        } finally {
            StudioRequestContextHolder.clear();
        }
    }
}
