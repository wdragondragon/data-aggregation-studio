package com.jdragon.studio.test;

import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.service.RunLogObjectStore;
import com.jdragon.studio.infra.service.RunLogStorageService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;

class RunLogStorageServiceRegressionTest {

    @Test
    void objectLogPagingShouldNotSplitUtf8Characters() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getRunLog().setStorageType(RunLogStorageService.STORAGE_OBJECT);
        properties.getRunLog().getObjectStorage().setBucket("logs");
        InMemoryObjectStore objectStore = new InMemoryObjectStore();
        objectStore.put("logs", "run.log", "abc中文def".getBytes(StandardCharsets.UTF_8), "text/plain;charset=UTF-8");

        RunRecordEntity entity = new RunRecordEntity();
        entity.setId(1L);
        entity.setLogObjectBucket("logs");
        entity.setLogObjectKey("run.log");
        entity.setLogCharset("UTF-8");

        RunLogView view = new RunLogStorageService(properties, objectStore, null)
                .readObjectLog(entity, 2, 4, false);

        assertFalse(view.getContent().contains("\uFFFD"));
    }

    private static final class InMemoryObjectStore implements RunLogObjectStore {
        private final Map<String, byte[]> values = new ConcurrentHashMap<String, byte[]>();

        @Override
        public void put(String bucket, String objectKey, byte[] bytes, String contentType) {
            values.put(bucket + "/" + objectKey, bytes);
        }

        @Override
        public byte[] get(String bucket, String objectKey) {
            return values.get(bucket + "/" + objectKey);
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
