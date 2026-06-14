package com.jdragon.studio.test;

import com.jdragon.studio.infra.service.NotificationTextSanitizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificationTextSanitizerTest {

    @Test
    void shouldRepairSinglePassUtf8Latin1MojibakeInMixedNotificationText() {
        String broken = "\u91c7\u96c6\u4efb\u52a1 SOAP1.2"
                + "\u00e5\u0085\u00a8\u00e9\u0093\u00be\u00e8\u00b7\u00af"
                + " \u672c\u6b21\u8fd0\u884c\u72b6\u6001\u4e3a FAILED\u3002";

        assertEquals("\u91c7\u96c6\u4efb\u52a1 SOAP1.2\u5168\u94fe\u8def \u672c\u6b21\u8fd0\u884c\u72b6\u6001\u4e3a FAILED\u3002",
                NotificationTextSanitizer.sanitize(broken));
    }

    @Test
    void shouldRepairDoublePassUtf8Latin1MojibakeInMixedNotificationText() {
        String broken = "\u91c7\u96c6\u4efb\u52a1 SOAP1.1"
                + "\u00c3\u00a5\u00c2\u0085\u00c2\u00a8\u00c3\u00a9\u00c2\u0093\u00c2\u00be\u00c3\u00a8\u00c2\u00b7\u00c2\u00af"
                + " \u672c\u6b21\u8fd0\u884c\u72b6\u6001\u4e3a SUCCESS\u3002";

        assertEquals("\u91c7\u96c6\u4efb\u52a1 SOAP1.1\u5168\u94fe\u8def \u672c\u6b21\u8fd0\u884c\u72b6\u6001\u4e3a SUCCESS\u3002",
                NotificationTextSanitizer.sanitize(broken));
    }

    @Test
    void shouldKeepNormalNotificationTextUnchanged() {
        String normal = "\u91c7\u96c6\u4efb\u52a1 SOAP1.2\u5168\u94fe\u8def \u672c\u6b21\u8fd0\u884c\u72b6\u6001\u4e3a SUCCESS\u3002";

        assertEquals(normal, NotificationTextSanitizer.sanitize(normal));
    }
}
