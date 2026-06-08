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

@Service
public class MinioRunLogObjectStore implements RunLogObjectStore {

    private static final long HEALTH_CACHE_MILLIS = 30000L;
    private static final String PROVIDER_MINIO = "minio";
    private static final String PROVIDER_OSS = "oss";

    private final StudioPlatformProperties properties;
    private volatile Boolean lastAvailable;
    private volatile long lastAvailableAt;

    public MinioRunLogObjectStore(StudioPlatformProperties properties) {
        this.properties = properties;
    }

    @Override
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
            throw new IllegalStateException("Failed to upload run log object " + objectKey, e);
        }
    }

    @Override
    public byte[] get(String bucket, String objectKey) {
        ensureConfigured(bucket, objectKey);
        ObjectPath path = splitObjectKey(objectKey);
        try (FileHelper helper = newHelper(bucket);
             InputStream inputStream = helper.getInputStream(path.path, path.name)) {
            return StreamUtils.copyToByteArray(inputStream);
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
                    // Readiness only requires that the worker can persist and read log content.
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    private FileHelper newHelper(String bucket) throws Exception {
        StudioPlatformProperties.ObjectStorageProperties objectStorage = objectStorage();
        if (!hasClientConfig(objectStorage)) {
            throw new IllegalStateException("Run log object storage is not configured");
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
            throw new IllegalStateException("Run log object storage connection failed");
        }
        return helper;
    }

    private StudioPlatformProperties.ObjectStorageProperties objectStorage() {
        return properties.getRunLog().getObjectStorage();
    }

    private boolean hasClientConfig(StudioPlatformProperties.ObjectStorageProperties objectStorage) {
        return objectStorage != null
                && hasText(objectStorage.getProvider())
                && hasText(objectStorage.getEndpoint())
                && hasText(objectStorage.getAccessKey())
                && hasText(objectStorage.getSecretKey());
    }

    private String normalizeProvider(String provider) {
        String value = hasText(provider) ? provider.trim().toLowerCase(java.util.Locale.ROOT) : PROVIDER_MINIO;
        if ("aliyun".equals(value) || "aliyun_oss".equals(value) || "aliyun-oss".equals(value)) {
            value = PROVIDER_OSS;
        }
        if (!PROVIDER_MINIO.equals(value) && !PROVIDER_OSS.equals(value)) {
            throw new IllegalArgumentException("Unsupported run log object storage provider: " + provider);
        }
        return value;
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

    private ObjectPath splitObjectKey(String objectKey) {
        String normalized = trimSlashes(objectKey == null ? "" : objectKey.replace('\\', '/'));
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
