package com.jdragon.studio.test;

import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.service.CloudObjectStorageService;
import com.jdragon.studio.infra.service.MinioRunLogObjectStore;
import com.jdragon.studio.infra.service.RunLogStorageService;
import com.jdragon.studio.worker.runtime.log.RunLogFileService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SharedObjectStorageRuntimeIT {

    @TempDir
    Path tempDir;

    @Test
    void workerArchiveShouldBeReadableAcrossNodesAndRecoverAfterStorageOutage() throws Exception {
        ObjectStorageEnvironment environment = ObjectStorageEnvironment.fromProcess();
        String prefix = "studio-it/run-logs/" + UUID.randomUUID();

        NodeStorage workerA = nodeStorage(tempDir.resolve("worker-a"), prefix, environment.endpoint);
        NodeStorage server = nodeStorage(tempDir.resolve("server"), prefix, environment.endpoint);
        NodeStorage workerB = nodeStorage(tempDir.resolve("worker-b"), prefix, environment.endpoint);

        assertThat(workerA.cloudStorage.available()).isTrue();
        RunLogFileService.PreparedRunLog archivedLog = workerA.runLogFileService.prepare(1001L);
        Files.writeString(archivedLog.getAbsolutePath(), "worker-a-archive-marker\n", StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);

        RunLogFileService.RunLogStorageResult archived = workerA.runLogFileService.finalizeLog(archivedLog);

        assertThat(archived.getStatus()).isEqualTo("AVAILABLE");
        assertThat(archived.getStorageType()).isEqualTo(RunLogStorageService.STORAGE_OBJECT);
        RunRecordEntity archivedRecord = objectRecord(1001L, archived);
        assertThat(server.storageService.readObjectLog(archivedRecord, 1, 64 * 1024, true).getContent())
                .contains("worker-a-archive-marker");
        assertThat(workerB.storageService.readObjectLog(archivedRecord, 1, 64 * 1024, true).getContent())
                .contains("worker-a-archive-marker");

        NodeStorage unavailableWorker = nodeStorage(tempDir.resolve("recovering-worker"), prefix,
                "http://127.0.0.1:1");
        RunLogFileService.PreparedRunLog failedLog = unavailableWorker.runLogFileService.prepare(2002L);
        Files.writeString(failedLog.getAbsolutePath(), "storage-recovery-marker\n", StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);

        RunLogFileService.RunLogStorageResult failed = unavailableWorker.runLogFileService.finalizeLog(failedLog);

        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getObjectKey()).isNull();
        assertThat(Files.readString(failedLog.getAbsolutePath(), StandardCharsets.UTF_8))
                .contains("storage-recovery-marker")
                .contains("Run log object storage upload failed");

        NodeStorage recoveredWorker = nodeStorage(tempDir.resolve("recovering-worker"), prefix,
                environment.endpoint);
        RunLogFileService.RunLogStorageResult recovered = recoveredWorker.runLogFileService.syncExistingLog(
                failedLog.getRelativePath(), failedLog.getCharset());

        assertThat(recovered.getStatus()).isEqualTo("AVAILABLE");
        RunLogView recoveredView = server.storageService.readObjectLog(
                objectRecord(2002L, recovered), 1, 64 * 1024, true);
        assertThat(recoveredView.getContent())
                .contains("storage-recovery-marker")
                .contains("Run log object storage upload failed");

        server.storageService.deleteObject(archived.getBucket(), archived.getObjectKey());
        server.storageService.deleteObject(recovered.getBucket(), recovered.getObjectKey());
    }

    private NodeStorage nodeStorage(Path runtimeLogDir, String prefix, String endpoint) {
        ObjectStorageEnvironment environment = ObjectStorageEnvironment.fromProcess();
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeLogDir(runtimeLogDir.toString());
        properties.getRunLog().setStorageType(RunLogStorageService.STORAGE_OBJECT);
        properties.getRunLog().setObjectPrefix(prefix);
        properties.getObjectStorage().setProvider(environment.provider);
        properties.getObjectStorage().setEndpoint(endpoint);
        properties.getObjectStorage().setAccessKey(environment.accessKey);
        properties.getObjectStorage().setSecretKey(environment.secretKey);
        properties.getObjectStorage().setBucket(environment.bucket);
        properties.getObjectStorage().setRegion(environment.region);
        properties.getObjectStorage().setCreateBucket(environment.createBucket);
        CloudObjectStorageService cloudStorage = new CloudObjectStorageService(properties);
        RunLogStorageService storageService = new RunLogStorageService(
                properties, new MinioRunLogObjectStore(cloudStorage), cloudStorage);
        return new NodeStorage(cloudStorage, storageService, new RunLogFileService(properties, storageService));
    }

    private RunRecordEntity objectRecord(Long id, RunLogFileService.RunLogStorageResult result) {
        RunRecordEntity entity = new RunRecordEntity();
        entity.setId(id);
        entity.setLogStorageType(result.getStorageType());
        entity.setLogObjectBucket(result.getBucket());
        entity.setLogObjectKey(result.getObjectKey());
        entity.setLogCharset(StandardCharsets.UTF_8.name());
        return entity;
    }

    private static final class NodeStorage {
        private final CloudObjectStorageService cloudStorage;
        private final RunLogStorageService storageService;
        private final RunLogFileService runLogFileService;

        private NodeStorage(CloudObjectStorageService cloudStorage,
                            RunLogStorageService storageService,
                            RunLogFileService runLogFileService) {
            this.cloudStorage = cloudStorage;
            this.storageService = storageService;
            this.runLogFileService = runLogFileService;
        }
    }

    private static final class ObjectStorageEnvironment {
        private final String provider;
        private final String endpoint;
        private final String accessKey;
        private final String secretKey;
        private final String bucket;
        private final String region;
        private final boolean createBucket;

        private ObjectStorageEnvironment(String provider,
                                         String endpoint,
                                         String accessKey,
                                         String secretKey,
                                         String bucket,
                                         String region,
                                         boolean createBucket) {
            this.provider = provider;
            this.endpoint = endpoint;
            this.accessKey = accessKey;
            this.secretKey = secretKey;
            this.bucket = bucket;
            this.region = region;
            this.createBucket = createBucket;
        }

        private static ObjectStorageEnvironment fromProcess() {
            String provider = normalizeProvider(System.getenv("STUDIO_IT_OBJECT_PROVIDER"));
            String endpoint = System.getenv("STUDIO_IT_OBJECT_ENDPOINT");
            String accessKey = System.getenv("STUDIO_IT_OBJECT_ACCESS_KEY");
            String secretKey = System.getenv("STUDIO_IT_OBJECT_SECRET_KEY");
            String bucket = System.getenv("STUDIO_IT_OBJECT_BUCKET");
            Assumptions.assumeTrue(hasText(endpoint) && hasText(accessKey) && hasText(secretKey) && hasText(bucket),
                    "Shared object storage integration environment is not configured");
            return new ObjectStorageEnvironment(provider, endpoint.trim(), accessKey.trim(), secretKey.trim(),
                    bucket.trim(), trimToNull(System.getenv("STUDIO_IT_OBJECT_REGION")),
                    booleanValue("STUDIO_IT_OBJECT_CREATE_BUCKET", false));
        }

        private static String normalizeProvider(String value) {
            String provider = hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "MINIO";
            if ("ALIYUN".equals(provider) || "ALIYUN_OSS".equals(provider) || "ALIYUN-OSS".equals(provider)) {
                return "OSS";
            }
            if (!"MINIO".equals(provider) && !"OSS".equals(provider)) {
                throw new IllegalArgumentException("STUDIO_IT_OBJECT_PROVIDER must be MINIO or OSS");
            }
            return provider;
        }

        private static boolean booleanValue(String name, boolean defaultValue) {
            String value = System.getenv(name);
            if (!hasText(value)) {
                return defaultValue;
            }
            if ("true".equalsIgnoreCase(value.trim())) {
                return true;
            }
            if ("false".equalsIgnoreCase(value.trim())) {
                return false;
            }
            throw new IllegalArgumentException(name + " must be true or false");
        }

        private static String trimToNull(String value) {
            return hasText(value) ? value.trim() : null;
        }

        private static boolean hasText(String value) {
            return value != null && !value.trim().isEmpty();
        }
    }
}
