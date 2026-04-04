package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.security.StudioUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class StudioSecurityService {

    public String currentTenantId() {
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

    public String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        return authentication.getName();
    }
}
