package com.jdragon.studio.infra.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StudioMobileSupportTest {

    @Test
    void shouldNormalizeSupportedMainlandMobileFormats() {
        assertEquals("13800000001", StudioMobileSupport.normalize("+86 138-0000-0001"));
        assertEquals("13800000001", StudioMobileSupport.normalize("8613800000001"));
    }

    @Test
    void shouldRejectNonMobileNumbers() {
        assertNull(StudioMobileSupport.normalize("010-12345678"));
        assertNull(StudioMobileSupport.normalize("not-a-mobile"));
    }
}
