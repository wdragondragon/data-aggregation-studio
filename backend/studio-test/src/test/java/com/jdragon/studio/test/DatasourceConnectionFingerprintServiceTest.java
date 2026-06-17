package com.jdragon.studio.test;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.DatasourceConnectionFingerprintService;
import com.jdragon.studio.infra.service.EncryptionService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatasourceConnectionFingerprintServiceTest {

    @Test
    void shouldGenerateSameFingerprintForSameTenantAndConnectionMetadata() {
        DatasourceConnectionFingerprintService service = fingerprintService();
        Map<String, Object> left = new LinkedHashMap<String, Object>();
        left.put("host", "127.0.0.1");
        left.put("port", 3306);
        left.put("database", "demo");
        left.put("password", "ENC(" + encryptionService().encrypt("secret") + ")");

        Map<String, Object> right = new LinkedHashMap<String, Object>();
        right.put("password", "ENC(" + encryptionService().encrypt("secret") + ")");
        right.put("database", "demo");
        right.put("port", 3306);
        right.put("host", "127.0.0.1");

        assertThat(service.fingerprint("tenant-a", "mysql8", left))
                .isEqualTo(service.fingerprint("tenant-a", "mysql8", right));
    }

    @Test
    void shouldSeparateDifferentTenants() {
        DatasourceConnectionFingerprintService service = fingerprintService();
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("host", "127.0.0.1");

        assertThat(service.fingerprint("tenant-a", "mysql8", metadata))
                .isNotEqualTo(service.fingerprint("tenant-b", "mysql8", metadata));
    }

    private DatasourceConnectionFingerprintService fingerprintService() {
        return new DatasourceConnectionFingerprintService(encryptionService(), properties());
    }

    private EncryptionService encryptionService() {
        return new EncryptionService(properties());
    }

    private StudioPlatformProperties properties() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setEncryptionSecret("fingerprint-test-secret");
        return properties;
    }
}
