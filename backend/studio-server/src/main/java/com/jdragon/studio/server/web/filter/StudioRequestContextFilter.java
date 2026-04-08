package com.jdragon.studio.server.web.filter;

import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.infra.security.StudioUserPrincipal;
import com.jdragon.studio.infra.service.StudioAccessService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class StudioRequestContextFilter extends OncePerRequestFilter {

    private final StudioAccessService studioAccessService;

    public StudioRequestContextFilter(StudioAccessService studioAccessService) {
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
