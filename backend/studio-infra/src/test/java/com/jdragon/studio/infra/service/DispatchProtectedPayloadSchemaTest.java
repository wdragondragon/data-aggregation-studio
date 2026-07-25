package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchProtectedPayloadSchemaTest {

    @Test
    void shouldKeepBaselineMigrationUpgradeAndRotationAligned() throws Exception {
        String mysql = readBackendFile("studio-server/src/main/resources/schema-mysql.sql");
        String sqlite = readBackendFile("studio-desktop-runtime/src/main/resources/schema-sqlite.sql");
        String migration = readBackendFile(
                "studio-server/src/main/resources/update/20260723/20260723-dispatch-protected-payload.sql");
        String upgrade = Files.readString(Path.of(
                "src/main/java/com/jdragon/studio/infra/service/StudioSchemaUpgradeService.java"),
                StandardCharsets.UTF_8);
        String rotation = Files.readString(Path.of(
                "src/main/java/com/jdragon/studio/infra/service/StudioEncryptionRotationService.java"),
                StandardCharsets.UTF_8);

        for (String source : new String[]{mysql, sqlite, migration, upgrade, rotation}) {
            assertTrue(source.contains("protected_payload_ciphertext"));
        }
        assertTrue(mysql.contains("protected_payload_ciphertext mediumtext"));
        assertTrue(sqlite.contains("protected_payload_ciphertext text"));
        assertTrue(migration.contains("protected_payload_ciphertext mediumtext"));
        assertFalse(migration.contains("access_token"));
        assertFalse(migration.contains("request_body"));
    }

    @Test
    void shouldNeverSerializeProtectedPayloadCiphertext() throws Exception {
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setId(1L);
        task.setProtectedPayloadCiphertext("ENC(secret)");

        String json = new ObjectMapper().writeValueAsString(task);

        assertFalse(json.contains("protectedPayloadCiphertext"));
        assertFalse(json.contains("ENC(secret)"));
    }

    private String readBackendFile(String relative) throws Exception {
        return Files.readString(Path.of("..").resolve(relative).normalize(), StandardCharsets.UTF_8);
    }
}
