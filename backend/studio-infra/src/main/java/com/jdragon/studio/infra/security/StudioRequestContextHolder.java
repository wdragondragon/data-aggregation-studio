package com.jdragon.studio.infra.security;

public final class StudioRequestContextHolder {

    private static final ThreadLocal<StudioRequestContext> CONTEXT = new ThreadLocal<StudioRequestContext>();

    private StudioRequestContextHolder() {
    }

    public static StudioRequestContext getContext() {
        return CONTEXT.get();
    }

    public static void setContext(StudioRequestContext context) {
        CONTEXT.set(context);
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
