package com.jdragon.studio.infra.service;

import java.util.regex.Pattern;

final class StudioMobileSupport {

    private static final Pattern MOBILE_PATTERN = Pattern.compile("^1\\d{10}$");

    private StudioMobileSupport() {
    }

    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replaceAll("[\\s-]", "");
        if (normalized.startsWith("+86")) {
            normalized = normalized.substring(3);
        } else if (normalized.length() == 13 && normalized.startsWith("86")) {
            normalized = normalized.substring(2);
        }
        return MOBILE_PATTERN.matcher(normalized).matches() ? normalized : null;
    }

    static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
