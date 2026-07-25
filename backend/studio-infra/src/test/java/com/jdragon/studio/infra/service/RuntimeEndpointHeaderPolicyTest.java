package com.jdragon.studio.infra.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeEndpointHeaderPolicyTest {

    private final RuntimeEndpointHeaderPolicy policy = new RuntimeEndpointHeaderPolicy();

    @Test
    void shouldFilterFixedReservedAndConnectionDeclaredHeaders() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer transport-secret");
        headers.put("X-Business-Trace", "trace-1");
        headers.put("Connection", "X-Remove-Me, x-second-hop");
        headers.put("X-Remove-Me", "blocked");
        headers.put("X-Second-Hop", "blocked");
        headers.put("Keep-Alive", "timeout=5");
        headers.put("Proxy-Authenticate", "challenge");
        headers.put("Proxy-Authorization", "credential");
        headers.put("Proxy-Connection", "keep-alive");
        headers.put("TE", "trailers");
        headers.put("Trailer", "Digest");
        headers.put("Transfer-Encoding", "chunked");
        headers.put("Upgrade", "websocket");
        headers.put("Host", "attacker.invalid");
        headers.put("Content-Length", "999");
        headers.put("X-Studio", "attacker");
        headers.put("X-Studio-Internal-Token", "attacker");
        headers.put("x-studio-custom", "attacker");

        Map<String, String> filtered = policy.sanitizeConfiguredHeaders(headers, Set.of());

        assertEquals(2, filtered.size());
        assertEquals("Bearer transport-secret", filtered.get("Authorization"));
        assertEquals("trace-1", filtered.get("X-Business-Trace"));
    }

    @Test
    void shouldNormalizeConnectionDeclarationsCaseInsensitively() {
        Set<String> declared = policy.connectionHeaderNames(Arrays.asList(
                " X-One ,X-Two",
                "x-three"));

        assertEquals(Set.of("x-one", "x-two", "x-three"), declared);
        assertTrue(policy.isHopByHop("X-ONE", declared));
        assertTrue(policy.isHopByHop("x-Two", declared));
        assertFalse(policy.isHopByHop("X-Business", declared));
        assertTrue(policy.isReservedStudioHeader("X-Studio-Internal-Token"));
        assertFalse(policy.isReservedStudioHeader("X-Business"));
    }

    @Test
    void shouldFilterAdditionalExcludedHeadersCaseInsensitively() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer transport-secret");
        headers.put("Accept", "application/json");
        headers.put("X-Business", "allowed");

        Map<String, String> filtered = policy.sanitizeConfiguredHeaders(
                headers, Set.of("authorization", "ACCEPT"));

        assertEquals(Map.of("X-Business", "allowed"), filtered);
    }

    @Test
    void shouldReturnEmptyMapForMissingConfiguredHeaders() {
        assertTrue(policy.sanitizeConfiguredHeaders(null, Set.of()).isEmpty());
        assertTrue(policy.sanitizeConfiguredHeaders(Map.of(), Set.of()).isEmpty());
    }
}
