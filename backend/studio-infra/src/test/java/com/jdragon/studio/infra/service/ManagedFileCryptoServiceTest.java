package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.ManagedFileEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagedFileCryptoServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void allowsBlankSecretOnlyWhileManagedFilesAreDisabled() {
        StudioPlatformProperties disabled = new StudioPlatformProperties();
        disabled.setEncryptionSecret("");
        disabled.getManagedFile().setEnabled(false);
        assertDoesNotThrow(() -> new ManagedFileCryptoService(disabled));

        StudioPlatformProperties enabled = new StudioPlatformProperties();
        enabled.setEncryptionSecret("");
        enabled.getManagedFile().setEnabled(true);
        assertThrows(StudioException.class, () -> new ManagedFileCryptoService(enabled));
    }

    @Test
    void encryptsDecryptsAndRejectsCiphertextTampering() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setEncryptionSecret("managed-file-test-secret");
        ManagedFileCryptoService crypto = new ManagedFileCryptoService(properties);
        ManagedFileEntity file = new ManagedFileEntity();
        file.setId(101L);
        file.setTenantId("tenant-a");
        file.setProjectId(201L);
        file.setPolicyCode("KERBEROS_KRB5_CONF");

        byte[] content = "[realms]\nEXAMPLE.COM = { kdc = kdc.example.com }\n"
                .getBytes(StandardCharsets.UTF_8);
        Path plaintext = tempDir.resolve("plain.conf");
        Path ciphertext = tempDir.resolve("encrypted.bin");
        Files.write(plaintext, content);
        ManagedFileCryptoService.EncryptionResult encrypted = crypto.encrypt(plaintext, ciphertext, file);
        file.setSha256(encrypted.getSha256());
        file.setPlaintextSize(encrypted.getPlaintextSize());
        file.setCiphertextSize(encrypted.getCiphertextSize());
        file.setEncryptionIv(encrypted.getIvBase64());

        Path restored = tempDir.resolve("restored.conf");
        crypto.decrypt(ciphertext, restored, file);
        assertArrayEquals(content, Files.readAllBytes(restored));

        byte[] tampered = Files.readAllBytes(ciphertext);
        tampered[tampered.length / 2] ^= 0x01;
        Files.write(ciphertext, tampered);
        assertThrows(StudioException.class,
                () -> crypto.decrypt(ciphertext, tempDir.resolve("tampered.conf"), file));
    }
}
