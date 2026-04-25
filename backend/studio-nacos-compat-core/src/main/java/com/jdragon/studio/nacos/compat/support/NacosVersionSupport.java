package com.jdragon.studio.nacos.compat.support;

public final class NacosVersionSupport {

    private NacosVersionSupport() {
    }

    public static int compare(String left, String right) {
        String[] leftParts = normalize(left).split("\\.");
        String[] rightParts = normalize(right).split("\\.");
        int max = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < max; i++) {
            int leftValue = i < leftParts.length ? parsePart(leftParts[i]) : 0;
            int rightValue = i < rightParts.length ? parsePart(rightParts[i]) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    public static boolean isModern(String version) {
        return compare(version, "2.5.1") >= 0;
    }

    private static String normalize(String version) {
        if (version == null || version.isBlank()) {
            return "0";
        }
        return version.trim().replaceAll("[^0-9.].*$", "");
    }

    private static int parsePart(String part) {
        if (part == null || part.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(part);
        }
        catch (NumberFormatException ex) {
            return 0;
        }
    }

}
