package com.jdragon.studio.infra.security;

import com.jdragon.studio.infra.security.StudioTokenResolver.ResolvedStudioToken;
import com.jdragon.studio.infra.security.StudioTokenResolver.StudioTokenSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StudioTokenResolverTest {

    private final StudioTokenResolver resolver = new StudioTokenResolver();

    @Test
    void explicitStudioHeaderMustWinOverCookieAndAuthorization() {
        ResolvedStudioToken resolved = resolver.resolve(
                "header-token", "cookie-token", "Bearer legacy-token");

        assertEquals("header-token", resolved.getToken());
        assertEquals(StudioTokenSource.STUDIO_HEADER, resolved.getSource());
    }

    @Test
    void cookieMustWinOverLegacyAuthorization() {
        ResolvedStudioToken resolved = resolver.resolve(
                null, "cookie-token", "Bearer legacy-token");

        assertEquals("cookie-token", resolved.getToken());
        assertEquals(StudioTokenSource.STUDIO_COOKIE, resolved.getSource());
    }

    @Test
    void legacyAuthorizationMustRemainCompatible() {
        ResolvedStudioToken resolved = resolver.resolve(
                null, null, "Bearer legacy-token");

        assertEquals("legacy-token", resolved.getToken());
        assertEquals(StudioTokenSource.LEGACY_AUTHORIZATION, resolved.getSource());
    }

    @Test
    void blankCredentialsMustNotProduceAuthenticationToken() {
        assertNull(resolver.resolve("  ", " ", "Bearer "));
    }
}
