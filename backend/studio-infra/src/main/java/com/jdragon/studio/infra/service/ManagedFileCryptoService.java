package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.ManagedFileEntity;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class ManagedFileCryptoService {

    public static final String ALGORITHM = "AES-256-GCM";
    public static final int FORMAT_VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final byte[] KEY_CONTEXT = "studio-managed-file-aes-gcm-v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public ManagedFileCryptoService(StudioPlatformProperties properties) {
        String secret = properties == null ? null : properties.getEncryptionSecret();
        boolean enabled = properties != null && properties.getManagedFile() != null
                && properties.getManagedFile().isEnabled();
        if ((secret == null || secret.trim().isEmpty()) && !enabled) {
            secret = "studio-managed-file-disabled";
        }
        this.key = new SecretKeySpec(deriveKey(secret), "AES");
    }

    public EncryptionResult encrypt(Path plaintext, Path ciphertext, ManagedFileEntity file) {
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(file));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0L;
            try (InputStream input = Files.newInputStream(plaintext);
                 OutputStream rawOutput = Files.newOutputStream(ciphertext,
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 CipherOutputStream encryptedOutput = new CipherOutputStream(rawOutput, cipher)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    digest.update(buffer, 0, read);
                    encryptedOutput.write(buffer, 0, read);
                    size += read;
                }
            }
            return new EncryptionResult(hex(digest.digest()), size, Files.size(ciphertext),
                    Base64.getEncoder().encodeToString(iv));
        } catch (Exception e) {
            deleteQuietly(ciphertext);
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to encrypt managed file", e);
        }
    }

    public void decrypt(Path ciphertext, Path plaintext, ManagedFileEntity file) {
        byte[] iv;
        try {
            iv = Base64.getDecoder().decode(file.getEncryptionIv());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad(file));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0L;
            try (InputStream rawInput = Files.newInputStream(ciphertext);
                 CipherInputStream decryptedInput = new CipherInputStream(rawInput, cipher);
                 OutputStream output = Files.newOutputStream(plaintext,
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = decryptedInput.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                    size += read;
                }
            }
            String actualHash = hex(digest.digest());
            if (file.getPlaintextSize() == null || file.getPlaintextSize().longValue() != size) {
                throw new IllegalStateException("Managed file plaintext size mismatch");
            }
            if (file.getSha256() == null || !file.getSha256().equalsIgnoreCase(actualHash)) {
                throw new IllegalStateException("Managed file SHA-256 mismatch");
            }
        } catch (Exception e) {
            deleteQuietly(plaintext);
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Managed file integrity verification failed", e);
        }
    }

    private byte[] aad(ManagedFileEntity file) {
        String value = file.getId() + "\n" + file.getTenantId() + "\n" + file.getProjectId()
                + "\n" + file.getPolicyCode();
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] deriveKey(String secret) {
        if (secret == null || secret.trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "studio.encryption-secret must not be blank when managed files are enabled");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(KEY_CONTEXT);
            digest.update((byte) 0);
            digest.update(secret.getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (Exception e) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to derive managed file encryption key", e);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // Staging cleanup is retried by the caller.
        }
    }

    public static final class EncryptionResult {
        private final String sha256;
        private final long plaintextSize;
        private final long ciphertextSize;
        private final String ivBase64;

        public EncryptionResult(String sha256, long plaintextSize, long ciphertextSize, String ivBase64) {
            this.sha256 = sha256;
            this.plaintextSize = plaintextSize;
            this.ciphertextSize = ciphertextSize;
            this.ivBase64 = ivBase64;
        }

        public String getSha256() { return sha256; }
        public long getPlaintextSize() { return plaintextSize; }
        public long getCiphertextSize() { return ciphertextSize; }
        public String getIvBase64() { return ivBase64; }
    }
}
