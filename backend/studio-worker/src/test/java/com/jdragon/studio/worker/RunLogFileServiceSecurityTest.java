package com.jdragon.studio.worker;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.RunLogObjectStore;
import com.jdragon.studio.infra.service.RunLogStorageService;
import com.jdragon.studio.worker.runtime.log.RunLogFileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RunLogFileServiceSecurityTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RunLogFileServiceSecurityTest.class);

    @TempDir
    Path tempDir;

    @Test
    void shouldRedactSensitiveValuesBeforeWritingTaskLogFile() throws Exception {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeLogDir(tempDir.toString());
        RunLogFileService service = new RunLogFileService(properties,
                new RunLogStorageService(properties, new NoopObjectStore(), null));
        RunLogFileService.PreparedRunLog prepared = service.prepare(42L);

        try (RunLogFileService.RunLogScope ignored = service.openScope(prepared)) {
            LOGGER.info("password=raw-secret Authorization: Bearer raw-token");
        }

        String content = Files.readString(prepared.getAbsolutePath(), StandardCharsets.UTF_8);
        assertThat(content).contains("password=******").contains("Authorization: ******");
        assertThat(content).doesNotContain("raw-secret").doesNotContain("raw-token");
    }

    private static final class NoopObjectStore implements RunLogObjectStore {
        @Override
        public void put(String bucket, String objectKey, byte[] bytes, String contentType) {
        }

        @Override
        public byte[] get(String bucket, String objectKey) {
            return new byte[0];
        }

        @Override
        public void delete(String bucket, String objectKey) {
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
