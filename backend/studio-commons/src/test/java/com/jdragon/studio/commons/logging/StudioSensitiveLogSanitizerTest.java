package com.jdragon.studio.commons.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudioSensitiveLogSanitizerTest {

    @Test
    void sanitizesSecretsAndDropsEmbeddedStackTrace() {
        String message = "Permission denied token=secret-value\r\n"
                + "\tat example.Plugin.mkdir(Plugin.java:42)";

        String sanitized = StudioSensitiveLogSanitizer.sanitizeSingleLine(message, 256);

        assertThat(sanitized)
                .isEqualTo("Permission denied token=******")
                .doesNotContain("secret-value", "Plugin.java", "\r", "\n");
    }

    @Test
    void boundsSingleLineValues() {
        String sanitized = StudioSensitiveLogSanitizer.sanitizeSingleLine("x".repeat(80), 32);

        assertThat(sanitized).hasSize(32).endsWith(" ...[truncated]");
    }

    @Test
    void rejectsInvalidMaximumLength() {
        assertThatThrownBy(() -> StudioSensitiveLogSanitizer.sanitizeSingleLine("value", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
