package com.jdragon.studio.infra.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
        try (ObjectStorageClient client = newClient(bucket)) {
            client.put(normalizeObjectKey(objectKey), bytes == null ? new byte[0] : bytes, contentType);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to upload object " + objectKey, e);
        }
    }

    public byte[] get(String bucket, String objectKey) {
        ensureConfigured(bucket, objectKey);
        try (ObjectStorageClient client = newClient(bucket)) {
            return client.get(normalizeObjectKey(objectKey));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read object " + objectKey, e);
        }
    }

    public void delete(String bucket, String objectKey) {
        ensureConfigured(bucket, objectKey);
        try (ObjectStorageClient client = newClient(bucket)) {
            client.delete(normalizeObjectKey(objectKey));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete object " + objectKey, e);
        }
    }

    private boolean probeWritable(StudioPlatformProperties.ObjectStorageProperties objectStorage) {
        String bucket = objectStorage.getBucket().trim();
        String objectKey = ".health/cloud-storage-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".txt";
        byte[] bytes = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (ObjectStorageClient client = newClient(bucket)) {
            client.put(objectKey, bytes, "text/plain");
            try {
                return client.get(objectKey).length == bytes.length;
            } finally {
                try {
                    client.delete(objectKey);
                } catch (Exception ignored) {
                    // Readiness only requires that the storage can persist and read content.
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    private ObjectStorageClient newClient(String bucket) throws Exception {
        StudioPlatformProperties.ObjectStorageProperties objectStorage = objectStorage();
        if (!hasClientConfig(objectStorage)) {
            throw new IllegalStateException("Object storage is not configured");
        }
        String provider = normalizeProvider(objectStorage.getProvider());
        if (PROVIDER_MINIO.equals(provider)) {
            return new MinioObjectStorageClient(objectStorage, bucket);
        }
        return new AliyunObjectStorageClient(objectStorage, bucket);
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

    private String normalizeObjectKey(String objectKey) {
        String normalized = trimSlashes(objectKey);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }
        return normalized;
    }

    private interface ObjectStorageClient extends AutoCloseable {
        void put(String objectKey, byte[] bytes, String contentType) throws Exception;

        byte[] get(String objectKey) throws Exception;

        void delete(String objectKey) throws Exception;
    }

    private static final class MinioObjectStorageClient implements ObjectStorageClient {
        private final MinioClient client;
        private final OkHttpClient httpClient;
        private final String bucket;

        private MinioObjectStorageClient(StudioPlatformProperties.ObjectStorageProperties properties,
                                         String bucket) throws Exception {
            this.httpClient = new OkHttpClient();
            this.client = MinioClient.builder()
                    .httpClient(httpClient)
                    .endpoint(properties.getEndpoint())
                    .credentials(properties.getAccessKey(), properties.getSecretKey())
                    .build();
            this.bucket = bucket;
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists && properties.isCreateBucket()) {
                MakeBucketArgs.Builder builder = MakeBucketArgs.builder().bucket(bucket);
                if (properties.getRegion() != null && !properties.getRegion().trim().isEmpty()) {
                    builder.region(properties.getRegion().trim());
                }
                client.makeBucket(builder.build());
                exists = true;
            }
            if (!exists) {
                throw new IllegalStateException("Object storage bucket does not exist: " + bucket);
            }
        }

        @Override
        public void put(String objectKey, byte[] bytes, String contentType) throws Exception {
            byte[] safeBytes = bytes == null ? new byte[0] : bytes;
            try (InputStream input = new ByteArrayInputStream(safeBytes)) {
                PutObjectArgs.Builder builder = PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(input, safeBytes.length, -1);
                if (contentType != null && !contentType.trim().isEmpty()) {
                    builder.contentType(contentType.trim());
                }
                client.putObject(builder.build());
            }
        }

        @Override
        public byte[] get(String objectKey) throws Exception {
            try (InputStream input = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build())) {
                return StreamUtils.copyToByteArray(input);
            }
        }

        @Override
        public void delete(String objectKey) throws Exception {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        }

        @Override
        public void close() {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }

    private static final class AliyunObjectStorageClient implements ObjectStorageClient {
        private final OSS client;
        private final String bucket;

        private AliyunObjectStorageClient(StudioPlatformProperties.ObjectStorageProperties properties,
                                          String bucket) {
            this.client = new OSSClientBuilder().build(
                    properties.getEndpoint(), properties.getAccessKey(), properties.getSecretKey());
            this.bucket = bucket;
            boolean exists = client.doesBucketExist(bucket);
            if (!exists && properties.isCreateBucket()) {
                client.createBucket(bucket);
                exists = true;
            }
            if (!exists) {
                client.shutdown();
                throw new IllegalStateException("Object storage bucket does not exist: " + bucket);
            }
        }

        @Override
        public void put(String objectKey, byte[] bytes, String contentType) {
            byte[] safeBytes = bytes == null ? new byte[0] : bytes;
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(safeBytes.length);
            if (contentType != null && !contentType.trim().isEmpty()) {
                metadata.setContentType(contentType.trim());
            }
            try (InputStream input = new ByteArrayInputStream(safeBytes)) {
                client.putObject(bucket, objectKey, input, metadata);
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("Failed to close object upload stream", ex);
            }
        }

        @Override
        public byte[] get(String objectKey) throws Exception {
            try (InputStream input = client.getObject(bucket, objectKey).getObjectContent()) {
                return StreamUtils.copyToByteArray(input);
            }
        }

        @Override
        public void delete(String objectKey) {
            client.deleteObject(bucket, objectKey);
        }

        @Override
        public void close() {
            client.shutdown();
        }
    }
}
