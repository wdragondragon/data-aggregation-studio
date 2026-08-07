package com.jdragon.studio.server.web.security;

import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.infra.security.StudioTokenResolver;
import com.jdragon.studio.infra.security.StudioTokenResolver.ResolvedStudioToken;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class StudioHttpTokenResolver {

    private final StudioTokenResolver tokenResolver = new StudioTokenResolver();

    public ResolvedStudioToken resolve(HttpServletRequest request) {
        return tokenResolver.resolve(
                request.getHeader(StudioConstants.STUDIO_TOKEN_HEADER),
                resolveCookieToken(request),
                request.getHeader(HttpHeaders.AUTHORIZATION));
    }

    private String resolveCookieToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (StudioConstants.STUDIO_TOKEN_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
