package com.jdragon.studio.infra.service;

import java.nio.charset.StandardCharsets;

public final class NotificationTextSanitizer {

    private static final int MAX_REPAIR_PASSES = 3;

    private NotificationTextSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String current = value;
        for (int i = 0; i < MAX_REPAIR_PASSES; i++) {
            String repaired = repairLatin1MojibakeRuns(current);
            if (current.equals(repaired)) {
                return current;
            }
            current = repaired;
        }
        return current;
    }

    private static String repairLatin1MojibakeRuns(String value) {
        StringBuilder result = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (!isLatin1NonAscii(current)) {
                result.append(current);
                index++;
                continue;
            }
            int start = index;
            boolean likelyMojibake = false;
            while (index < value.length() && isLatin1NonAscii(value.charAt(index))) {
                if (isMojibakeIndicator(value.charAt(index))) {
                    likelyMojibake = true;
                }
                index++;
            }
            String run = value.substring(start, index);
            result.append(likelyMojibake ? decodeLatin1Run(run) : run);
        }
        return result.toString();
    }

    private static boolean isLatin1NonAscii(char value) {
        return value >= 0x80 && value <= 0xff;
    }

    private static boolean isMojibakeIndicator(char value) {
        return value == '\u00c2' || value == '\u00c3' || (value >= 0x80 && value <= 0x9f);
    }

    private static String decodeLatin1Run(String value) {
        String decoded = new String(value.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        return decoded.indexOf('\ufffd') >= 0 ? value : decoded;
    }
}
