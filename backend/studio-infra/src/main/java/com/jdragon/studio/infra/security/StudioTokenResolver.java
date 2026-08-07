package com.jdragon.studio.infra.security;

public class StudioTokenResolver {

    private static final String BEARER_PREFIX = "Bearer ";

    public ResolvedStudioToken resolve(String studioHeaderToken,
                                       String studioCookieToken,
                                       String authorizationHeader) {
        String headerToken = trimToNull(studioHeaderToken);
        if (headerToken != null) {
            return new ResolvedStudioToken(headerToken, StudioTokenSource.STUDIO_HEADER);
        }

        String cookieToken = trimToNull(studioCookieToken);
        if (cookieToken != null) {
            return new ResolvedStudioToken(cookieToken, StudioTokenSource.STUDIO_COOKIE);
        }

        String authorization = trimToNull(authorizationHeader);
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            String token = trimToNull(authorization.substring(BEARER_PREFIX.length()));
            if (token != null) {
                return new ResolvedStudioToken(token, StudioTokenSource.LEGACY_AUTHORIZATION);
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public enum StudioTokenSource {
        STUDIO_HEADER,
        STUDIO_COOKIE,
        LEGACY_AUTHORIZATION
    }

    public static final class ResolvedStudioToken {
        private final String token;
        private final StudioTokenSource source;

        public ResolvedStudioToken(String token, StudioTokenSource source) {
            this.token = token;
            this.source = source;
        }

        public String getToken() {
            return token;
        }

        public StudioTokenSource getSource() {
            return source;
        }
    }
}
