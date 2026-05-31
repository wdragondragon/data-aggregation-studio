package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MinioRunLogObjectStore implements RunLogObjectStore {

    private static final long HEALTH_CACHE_MILLIS = 30000L;

    private final StudioPlatformProperties properties;
    private final Map<String, Boolean> checkedBuckets = new ConcurrentHashMap<String, Boolean>();
    private volatile MinioClient client;
    private volatile Boolean lastAvailable;
    private volatile long lastAvailableAt;

    public MinioRunLogObjectStore(StudioPlatformProperties properties) {
        this.properties = properties;
    }

    @Override
    public void put(String bucket, String objectKey, byte[] bytes, String contentType) {
        ensureConfigured(bucket, objectKey);
        try {
            ensureBucket(bucket);
            byte[] safeBytes = bytes == null ? new byte[0] : bytes;
            getClient().putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .contentType(contentType == null || contentType.trim().isEmpty() ? "text/plain;charset=UTF-8" : contentType)
                    .stream(new ByteArrayInputStream(safeBytes), safeBytes.length, -1)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to upload run log object " + objectKey, e);
        }
    }

    @Override
    public byte[] get(String bucket, String objectKey) {
        ensureConfigured(bucket, objectKey);
        try (InputStream inputStream = getClient().getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            return StreamUtils.copyToByteArray(inputStream);
        } catch (ErrorResponseException e) {
            throw new IllegalStateException("Run log object not found: " + objectKey, e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read run log object " + objectKey, e);
        }
    }

    @Override
    public boolean available() {
        StudioPlatformProperties.ObjectStorageProperties objectStorage = objectStorage();
        if (!hasClientConfig(objectStorage) || !hasText(objectStorage.getBucket())) {
            return false;
        }
        long now = System.currentTimeMillis();
        Boolean cached = lastAvailable;
        if (cached != null && now - lastAvailableAt < HEALTH_CACHE_MILLIS) {
            return cached.booleanValue();
        }
        boolean available = probeWritable(objectStorage);
        lastAvailable = Boolean.valueOf(available);
        lastAvailableAt = now;
        return available;
    }

    private boolean probeWritable(StudioPlatformProperties.ObjectStorageProperties objectStorage) {
        String bucket = objectStorage.getBucket().trim();
        String objectKey = healthObjectKey(objectStorage);
        byte[] bytes = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            ensureBucket(bucket);
            getClient().putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .contentType("text/plain;charset=UTF-8")
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .build());
            try (InputStream inputStream = getClient().getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build())) {
                byte[] stored = StreamUtils.copyToByteArray(inputStream);
                return stored.length == bytes.length;
            } finally {
                try {
                    getClient().removeObject(RemoveObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build());
                } catch (Exception ignored) {
                    // Readiness only requires that the worker can persist and read log content.
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureBucket(String bucket) throws Exception {
        if (!objectStorage().isCreateBucket() || checkedBuckets.containsKey(bucket)) {
            return;
        }
        boolean exists = getClient().bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            getClient().makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        checkedBuckets.put(bucket, Boolean.TRUE);
    }

    private MinioClient getClient() {
        MinioClient current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                StudioPlatformProperties.ObjectStorageProperties objectStorage = objectStorage();
                if (!hasClientConfig(objectStorage)) {
                    throw new IllegalStateException("Run log object storage is not configured");
                }
                MinioClient.Builder builder = MinioClient.builder()
                        .endpoint(objectStorage.getEndpoint())
                        .credentials(objectStorage.getAccessKey(), objectStorage.getSecretKey());
                if (hasText(objectStorage.getRegion())) {
                    builder.region(objectStorage.getRegion().trim());
                }
                client = builder.build();
            }
            return client;
        }
    }

    private StudioPlatformProperties.ObjectStorageProperties objectStorage() {
        return properties.getRunLog().getObjectStorage();
    }

    private boolean hasClientConfig(StudioPlatformProperties.ObjectStorageProperties objectStorage) {
        return objectStorage != null
                && hasText(objectStorage.getEndpoint())
                && hasText(objectStorage.getAccessKey())
                && hasText(objectStorage.getSecretKey());
    }

    private String healthObjectKey(StudioPlatformProperties.ObjectStorageProperties objectStorage) {
        String prefix = hasText(objectStorage.getPrefix()) ? trimSlashes(objectStorage.getPrefix().trim()) : "";
        String suffix = ".health/run-log-" + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID() + ".txt";
        return prefix.isEmpty() ? trimSlashes(suffix) : prefix + "/" + trimSlashes(suffix);
    }

    private void ensureConfigured(String bucket, String objectKey) {
        if (!hasText(bucket)) {
            throw new IllegalArgumentException("bucket must not be blank");
        }
        if (!hasText(objectKey)) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String trimSlashes(String value) {
        String result = value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
