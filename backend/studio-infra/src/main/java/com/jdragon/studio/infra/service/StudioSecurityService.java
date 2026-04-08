package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.infra.security.StudioUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class StudioSecurityService {

    public String currentTenantId() {
        StudioRequestContext requestContext = StudioRequestContextHolder.getContext();
        if (requestContext != null && requestContext.getTenantId() != null && !requestContext.getTenantId().trim().isEmpty()) {
            return requestContext.getTenantId();
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            return StudioConstants.DEFAULT_TENANT_ID;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof StudioUserPrincipal) {
            StudioUserPrincipal studioUserPrincipal = (StudioUserPrincipal) principal;
            if (studioUserPrincipal.getTenantId() != null && !studioUserPrincipal.getTenantId().trim().isEmpty()) {
                return studioUserPrincipal.getTenantId();
            }
        }
        return StudioConstants.DEFAULT_TENANT_ID;
    }

    public Long currentProjectId() {
        StudioRequestContext requestContext = StudioRequestContextHolder.getContext();
        return requestContext == null ? null : requestContext.getProjectId();
    }

    public Long currentUserId() {
        StudioRequestContext requestContext = StudioRequestContextHolder.getContext();
        if (requestContext != null) {
            return requestContext.getUserId();
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof StudioUserPrincipal)) {
            return null;
        }
        return ((StudioUserPrincipal) authentication.getPrincipal()).getUserId();
    }

    public String currentUsername() {
        StudioRequestContext requestContext = StudioRequestContextHolder.getContext();
        if (requestContext != null && requestContext.getUsername() != null) {
            return requestContext.getUsername();
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        return authentication.getName();
    }

    public List<String> currentRoleCodes() {
        StudioRequestContext requestContext = StudioRequestContextHolder.getContext();
        if (requestContext == null || requestContext.getEffectiveRoleCodes() == null) {
            return Collections.emptyList();
        }
        return requestContext.getEffectiveRoleCodes();
    }
}
