package com.jdragon.studio.infra.service;

import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.datasource.file.FileHelper;
import com.jdragon.aggregation.datasource.file.s3.minio.MinioSourcePlugin;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;

@Service
public class CloudObjectStorageService {

    private static final long HEALTH_CACHE_MILLIS = 30000L;
    private static final String PROVIDER_MINIO = "minio";
    private static final String PROVIDER_OSS = "oss";

    private final StudioPlatformProperties properties;
    private volatile Boolean lastAvailable;
    private volatile long lastAvailableAt;

    public CloudObjectStorageService(StudioPlatformProperties properties) {
        this.properties = properties;
    }

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

    public boolean bucketConfigured() {
        return hasText(objectStorage().getBucket());
    }

    public String resolveBucket() {
        String bucket = objectStorage().getBucket();
        if (!hasText(bucket)) {
            throw new IllegalStateException("studio.object-storage.bucket is required");
        }
        return bucket.trim();
    }

    public void put(String bucket, String objectKey, byte[] bytes, String contentType) {
        ensureConfigured(bucket, objectKey);
        try (FileHelper helper = newHelper(bucket)) {
            byte[] safeBytes = bytes == null ? new byte[0] : bytes;
            ObjectPath path = splitObjectKey(objectKey);
            try (OutputStream outputStream = helper.getOutputStream(path.path, path.name);
                 InputStream inputStream = new ByteArrayInputStream(safeBytes)) {
                StreamUtils.copy(inputStream, outputStream);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to upload object " + objectKey, e);
        }
    }

    public byte[] get(String bucket, String objectKey) {
        ensureConfigured(bucket, objectKey);
        ObjectPath path = splitObjectKey(objectKey);
        try (FileHelper helper = newHelper(bucket);
             InputStream inputStream = helper.getInputStream(path.path, path.name)) {
            return StreamUtils.copyToByteArray(inputStream);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read object " + objectKey, e);
        }
    }

    public void delete(String bucket, String objectKey) {
        ensureConfigured(bucket, objectKey);
        try (FileHelper helper = newHelper(bucket)) {
            helper.rm(trimSlashes(objectKey));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete object " + objectKey, e);
        }
    }

    private boolean probeWritable(StudioPlatformProperties.ObjectStorageProperties objectStorage) {
        String bucket = objectStorage.getBucket().trim();
        String objectKey = ".health/cloud-storage-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".txt";
        byte[] bytes = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ObjectPath path = splitObjectKey(objectKey);
        try (FileHelper helper = newHelper(bucket)) {
            try (OutputStream outputStream = helper.getOutputStream(path.path, path.name);
                 InputStream inputStream = new ByteArrayInputStream(bytes)) {
                StreamUtils.copy(inputStream, outputStream);
            }
            try (InputStream inputStream = helper.getInputStream(path.path, path.name)) {
                byte[] stored = StreamUtils.copyToByteArray(inputStream);
                return stored.length == bytes.length;
            } finally {
                try {
                    helper.rm(objectKey);
                } catch (Exception ignored) {
                    // Readiness only requires that the storage can persist and read content.
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    private FileHelper newHelper(String bucket) throws Exception {
        StudioPlatformProperties.ObjectStorageProperties objectStorage = objectStorage();
        if (!hasClientConfig(objectStorage)) {
            throw new IllegalStateException("Object storage is not configured");
        }
        MinioSourcePlugin helper = new MinioSourcePlugin();
        Configuration configuration = Configuration.newDefault();
        configuration.set("storageProvider", normalizeProvider(objectStorage.getProvider()));
        configuration.set("endpoint", objectStorage.getEndpoint());
        configuration.set("accessKey", objectStorage.getAccessKey());
        configuration.set("secretKey", objectStorage.getSecretKey());
        configuration.set("bucket", bucket);
        configuration.set("region", objectStorage.getRegion());
        configuration.set("createBucket", objectStorage.isCreateBucket());
        boolean connected = helper.connect(configuration);
        if (!connected || !helper.isConnected()) {
            throw new IllegalStateException("Object storage connection failed");
        }
        return helper;
    }

    private StudioPlatformProperties.ObjectStorageProperties objectStorage() {
        StudioPlatformProperties.ObjectStorageProperties current = properties.getObjectStorage();
        if (hasClientConfig(current) && hasText(current.getBucket())) {
            return current;
        }
        StudioPlatformProperties.RunLogProperties runLog = properties.getRunLog();
        StudioPlatformProperties.ObjectStorageProperties legacy = runLog == null ? null : runLog.getObjectStorage();
        if (hasClientConfig(legacy) || hasText(legacy == null ? null : legacy.getBucket())) {
            return legacy;
        }
        return current == null ? new StudioPlatformProperties.ObjectStorageProperties() : current;
    }

    private boolean hasClientConfig(StudioPlatformProperties.ObjectStorageProperties objectStorage) {
        return objectStorage != null
                && hasText(objectStorage.getProvider())
                && hasText(objectStorage.getEndpoint())
                && hasText(objectStorage.getAccessKey())
                && hasText(objectStorage.getSecretKey());
    }

    private String normalizeProvider(String provider) {
        String value = hasText(provider) ? provider.trim().toLowerCase(Locale.ROOT) : PROVIDER_MINIO;
        if ("aliyun".equals(value) || "aliyun_oss".equals(value) || "aliyun-oss".equals(value)) {
            value = PROVIDER_OSS;
        }
        if (!PROVIDER_MINIO.equals(value) && !PROVIDER_OSS.equals(value)) {
            throw new IllegalArgumentException("Unsupported object storage provider: " + provider);
        }
        return value;
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
        String result = value == null ? "" : value.replace('\\', '/');
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private ObjectPath splitObjectKey(String objectKey) {
        String normalized = trimSlashes(objectKey);
        int index = normalized.lastIndexOf('/');
        if (index < 0) {
            return new ObjectPath("", normalized);
        }
        return new ObjectPath(normalized.substring(0, index), normalized.substring(index + 1));
    }

    private static final class ObjectPath {
        private final String path;
        private final String name;

        private ObjectPath(String path, String name) {
            this.path = path;
            this.name = name;
        }
    }
}
