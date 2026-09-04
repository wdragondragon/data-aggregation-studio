package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KerberosConfigRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void mergesDifferentRealmsAndUsesDeterministicDefaultRealm() throws Exception {
        KerberosConfigRegistry registry = registry();
        Path realmA = config("a.conf", "A.EXAMPLE", "kdc-a.example", ".a.example", "A.EXAMPLE");
        Path realmB = config("b.conf", "B.EXAMPLE", "kdc-b.example", ".b.example", "B.EXAMPLE");

        try (KerberosConfigRegistry.Activation first = registry.activate(
                realmA, 1L, "kafka/broker.a.example@A.EXAMPLE", "broker.a.example");
             KerberosConfigRegistry.Activation second = registry.activate(
                     realmB, 2L, "kafka/broker.b.example@B.EXAMPLE", "broker.b.example")) {
            String merged = Files.readString(second.getMergedPath(), StandardCharsets.UTF_8);
            assertEquals("A.EXAMPLE", second.getDefaultRealm());
            assertTrue(merged.contains("default_realm = A.EXAMPLE"));
            assertTrue(merged.contains("A.EXAMPLE = {kdc=kdc-a.example}"));
            assertTrue(merged.contains("B.EXAMPLE = {kdc=kdc-b.example}"));
        }
    }

    @Test
    void conflictingRealmDoesNotModifyStableConfiguration() throws Exception {
        KerberosConfigRegistry registry = registry();
        Path firstConfig = config("first.conf", "SAME.EXAMPLE", "kdc-one.example",
                ".same.example", "SAME.EXAMPLE");
        Path conflict = config("conflict.conf", "SAME.EXAMPLE", "kdc-two.example",
                ".same.example", "SAME.EXAMPLE");

        try (KerberosConfigRegistry.Activation first = registry.activate(
                firstConfig, 1L, "kafka/broker.same.example@SAME.EXAMPLE", "broker.same.example")) {
            String before = Files.readString(first.getMergedPath(), StandardCharsets.UTF_8);
            assertThrows(StudioException.class, () -> registry.activate(
                    conflict, 2L, "kafka/broker.same.example@SAME.EXAMPLE", "broker.same.example"));
            assertEquals(before, Files.readString(first.getMergedPath(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void rejectsNonDefaultRealmWithoutDomainMappingBeforeReplacingStableFile() throws Exception {
        KerberosConfigRegistry registry = registry();
        Path realmA = config("a.conf", "A.EXAMPLE", "kdc-a.example", ".a.example", "A.EXAMPLE");
        Path realmB = config("b.conf", "B.EXAMPLE", "kdc-b.example", null, null);

        try (KerberosConfigRegistry.Activation first = registry.activate(
                realmA, 1L, "kafka/broker.a.example@A.EXAMPLE", "broker.a.example")) {
            String before = Files.readString(first.getMergedPath(), StandardCharsets.UTF_8);
            assertThrows(StudioException.class, () -> registry.activate(
                    realmB, 2L, "kafka/broker.b.example@B.EXAMPLE", "broker.b.example"));
            assertEquals(before, Files.readString(first.getMergedPath(), StandardCharsets.UTF_8));
        }
    }

    private KerberosConfigRegistry registry() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        StudioPlatformProperties.ManagedFileProperties managed = new StudioPlatformProperties.ManagedFileProperties();
        managed.setCacheDir(tempDir.resolve("cache").toString());
        properties.setManagedFile(managed);
        return new KerberosConfigRegistry(properties);
    }

    private Path config(String fileName, String realm, String kdc,
                        String domain, String mappedRealm) throws Exception {
        StringBuilder content = new StringBuilder();
        content.append("[libdefaults]\n").append("dns_lookup_kdc = false\n")
                .append("[realms]\n").append(realm).append(" = { kdc = ").append(kdc).append(" }\n");
        if (domain != null) {
            content.append("[domain_realm]\n").append(domain).append(" = ").append(mappedRealm).append('\n');
        }
        Path path = tempDir.resolve(fileName);
        Files.writeString(path, content.toString(), StandardCharsets.UTF_8);
        return path;
    }
}
