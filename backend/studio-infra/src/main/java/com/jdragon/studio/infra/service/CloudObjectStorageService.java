package com.jdragon.studio.infra.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.UploadObjectArgs;
import io.minio.errors.ErrorResponseException;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collections;
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
        try (ObjectStorageClient client = newClient(bucket, true)) {
            client.put(normalizeObjectKey(objectKey), bytes == null ? new byte[0] : bytes, contentType);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to upload object " + objectKey, e);
        }
    }

    public void putFile(String bucket, String objectKey, Path source, String contentType) {
        putFile(bucket, objectKey, source, contentType, true);
    }

    /** Uploads a local file using the provider's streaming/file API. */
    public void putFileExistingBucket(String bucket, String objectKey, Path source, String contentType) {
        putFile(bucket, objectKey, source, contentType, false);
    }

    private void putFile(String bucket, String objectKey, Path source, String contentType,
                         boolean allowBucketCreation) {
        ensureConfigured(bucket, objectKey);
        if (source == null || !Files.isRegularFile(source)) {
            throw new IllegalArgumentException("source must be a regular file");
        }
        Path normalizedSource = source.toAbsolutePath().normalize();
        try (ObjectStorageClient client = newClient(bucket, allowBucketCreation)) {
            client.putFile(normalizeObjectKey(objectKey), normalizedSource, contentType);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to upload object " + objectKey, e);
        }
    }

    /**
     * Atomically creates an immutable object when the key is absent. Returns false when
     * another publisher already created the key and never overwrites that object.
     */
    public boolean putFileIfAbsent(String bucket, String objectKey, Path source, String contentType) {
        ensureConfigured(bucket, objectKey);
        if (source == null || !Files.isRegularFile(source)) {
            throw new IllegalArgumentException("source must be a regular file");
        }
        Path normalizedSource = source.toAbsolutePath().normalize();
        try (ObjectStorageClient client = newClient(bucket, false)) {
            return client.putFileIfAbsent(normalizeObjectKey(objectKey), normalizedSource, contentType);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create immutable object " + objectKey, e);
        }
    }

    public byte[] get(String bucket, String objectKey) {
        ensureConfigured(bucket, objectKey);
        try (ObjectStorageClient client = newClient(bucket, false)) {
            return client.get(normalizeObjectKey(objectKey));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read object " + objectKey, e);
        }
    }

    public ObjectInfo stat(String bucket, String objectKey) {
        ensureConfigured(bucket, objectKey);
        try (ObjectStorageClient client = newClient(bucket, false)) {
            return client.stat(normalizeObjectKey(objectKey));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to stat object " + objectKey, e);
        }
    }

    public boolean exists(String bucket, String objectKey) {
        ensureConfigured(bucket, objectKey);
        try (ObjectStorageClient client = newClient(bucket, false)) {
            return client.exists(normalizeObjectKey(objectKey));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to check object " + objectKey, e);
        }
    }

    public void downloadTo(String bucket, String objectKey, Path target) {
        downloadTo(bucket, objectKey, target, Long.MAX_VALUE);
    }

    /**
     * Streams an object to a local file while enforcing a hard byte limit during the copy.
     * The limit protects the Worker from a mutable object changing after its metadata was read.
     */
    public void downloadTo(String bucket, String objectKey, Path target, long maxBytes) {
        ensureConfigured(bucket, objectKey);
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes must not be negative");
        }
        Path normalizedTarget = target.toAbsolutePath().normalize();
        try {
            if (normalizedTarget.getParent() != null) {
                Files.createDirectories(normalizedTarget.getParent());
            }
            try (ObjectStorageClient client = newClient(bucket, false)) {
                client.download(normalizeObjectKey(objectKey), normalizedTarget, maxBytes);
            }
        } catch (Exception e) {
            try {
                Files.deleteIfExists(normalizedTarget);
            } catch (Exception ignored) {
                // The primary failure is more useful to the caller; staging cleanup retries later.
            }
            throw new IllegalStateException("Failed to download object " + objectKey, e);
        }
    }

    /** Streams an object directly to the caller without retaining it in memory. */
    public void downloadTo(String bucket, String objectKey, OutputStream output) {
        ensureConfigured(bucket, objectKey);
        if (output == null) {
            throw new IllegalArgumentException("output must not be null");
        }
        try (ObjectStorageClient client = newClient(bucket, false)) {
            client.download(normalizeObjectKey(objectKey), output);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to stream object " + objectKey, e);
        }
    }

    public void delete(String bucket, String objectKey) {
        ensureConfigured(bucket, objectKey);
        try (ObjectStorageClient client = newClient(bucket, false)) {
            client.delete(normalizeObjectKey(objectKey));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete object " + objectKey, e);
        }
    }

    private boolean probeWritable(StudioPlatformProperties.ObjectStorageProperties objectStorage) {
        String bucket = objectStorage.getBucket().trim();
        String objectKey = ".health/cloud-storage-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".txt";
        byte[] bytes = "ok".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (ObjectStorageClient client = newClient(bucket, true)) {
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

    private ObjectStorageClient newClient(String bucket, boolean allowBucketCreation) throws Exception {
        StudioPlatformProperties.ObjectStorageProperties objectStorage = objectStorage();
        if (!hasClientConfig(objectStorage)) {
            throw new IllegalStateException("Object storage is not configured");
        }
        String provider = normalizeProvider(objectStorage.getProvider());
        if (PROVIDER_MINIO.equals(provider)) {
            return new MinioObjectStorageClient(objectStorage, bucket, allowBucketCreation);
        }
        return new AliyunObjectStorageClient(objectStorage, bucket, allowBucketCreation);
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

        void putFile(String objectKey, Path source, String contentType) throws Exception;

        boolean putFileIfAbsent(String objectKey, Path source, String contentType) throws Exception;

        byte[] get(String objectKey) throws Exception;

        ObjectInfo stat(String objectKey) throws Exception;

        boolean exists(String objectKey) throws Exception;

        void download(String objectKey, Path target, long maxBytes) throws Exception;

        void download(String objectKey, OutputStream output) throws Exception;

        void delete(String objectKey) throws Exception;

    }

    private static final class MinioObjectStorageClient implements ObjectStorageClient {
        private final MinioClient client;
        private final OkHttpClient httpClient;
        private final String bucket;

        private MinioObjectStorageClient(StudioPlatformProperties.ObjectStorageProperties properties,
                                         String bucket,
                                         boolean allowBucketCreation) throws Exception {
            this.httpClient = new OkHttpClient();
            this.client = MinioClient.builder()
                    .httpClient(httpClient)
                    .endpoint(properties.getEndpoint())
                    .credentials(properties.getAccessKey(), properties.getSecretKey())
                    .build();
            this.bucket = bucket;
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists && allowBucketCreation && properties.isCreateBucket()) {
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
        public void putFile(String objectKey, Path source, String contentType) throws Exception {
            client.uploadObject(UploadObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .filename(source.toString())
                    .contentType(hasTextStatic(contentType) ? contentType : "application/octet-stream")
                    .build());
        }

        @Override
        public boolean putFileIfAbsent(String objectKey, Path source, String contentType) throws Exception {
            try {
                client.uploadObject(UploadObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .filename(source.toString())
                        .contentType(hasTextStatic(contentType) ? contentType : "application/octet-stream")
                        .extraHeaders(Collections.singletonMap("If-None-Match", "*"))
                        .build());
                return true;
            } catch (ErrorResponseException ex) {
                String code = ex.errorResponse() == null ? null : ex.errorResponse().code();
                if (isImmutableCreateConflict(code)) {
                    return false;
                }
                throw ex;
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
        public ObjectInfo stat(String objectKey) throws Exception {
            StatObjectResponse response = client.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
            Instant lastModified = response.lastModified() == null
                    ? null : response.lastModified().toInstant();
            return new ObjectInfo(response.size(), response.etag(), response.versionId(), lastModified);
        }

        @Override
        public boolean exists(String objectKey) throws Exception {
            try {
                client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
                return true;
            } catch (ErrorResponseException ex) {
                String code = ex.errorResponse() == null ? null : ex.errorResponse().code();
                if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)
                        || "NoSuchBucket".equals(code)) {
                    return false;
                }
                throw ex;
            }
        }

        @Override
        public void download(String objectKey, Path target, long maxBytes) throws Exception {
            try (InputStream input = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
                 OutputStream output = Files.newOutputStream(target,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE)) {
                copyToLimited(input, output, maxBytes);
            }
        }

        @Override
        public void download(String objectKey, OutputStream output) throws Exception {
            try (InputStream input = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build())) {
                copyToLimited(input, output, Long.MAX_VALUE);
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
                                          String bucket,
                                          boolean allowBucketCreation) {
            this.client = new OSSClientBuilder().build(
                    properties.getEndpoint(), properties.getAccessKey(), properties.getSecretKey());
            this.bucket = bucket;
            boolean exists = client.doesBucketExist(bucket);
            if (!exists && allowBucketCreation && properties.isCreateBucket()) {
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
        public void putFile(String objectKey, Path source, String contentType) throws Exception {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(Files.size(source));
            metadata.setContentType(hasTextStatic(contentType) ? contentType : "application/octet-stream");
            try (InputStream input = Files.newInputStream(source)) {
                client.putObject(bucket, objectKey, input, metadata);
            }
        }

        @Override
        public boolean putFileIfAbsent(String objectKey, Path source, String contentType) throws Exception {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(Files.size(source));
            metadata.setContentType(hasTextStatic(contentType) ? contentType : "application/octet-stream");
            metadata.setHeader("x-oss-forbid-overwrite", "true");
            try (InputStream input = Files.newInputStream(source)) {
                client.putObject(bucket, objectKey, input, metadata);
                return true;
            } catch (OSSException ex) {
                if (isImmutableCreateConflict(ex.getErrorCode())) {
                    return false;
                }
                // Some Aliyun-compatible endpoints return InvalidResponse for
                // x-oss-forbid-overwrite conflicts. Treat it as idempotent only
                // after the target object is observable; the caller then verifies
                // the complete object size and digest.
                if (isIndeterminateImmutableCreateResponse(ex.getErrorCode())
                        && client.doesObjectExist(bucket, objectKey)) {
                    return false;
                }
                throw ex;
            }
        }

        @Override
        public byte[] get(String objectKey) throws Exception {
            try (InputStream input = client.getObject(bucket, objectKey).getObjectContent()) {
                return StreamUtils.copyToByteArray(input);
            }
        }

        @Override
        public ObjectInfo stat(String objectKey) {
            ObjectMetadata metadata = client.getObjectMetadata(bucket, objectKey);
            Instant lastModified = metadata.getLastModified() == null
                    ? null : metadata.getLastModified().toInstant();
            return new ObjectInfo(metadata.getContentLength(), metadata.getETag(),
                    metadata.getVersionId(), lastModified);
        }

        @Override
        public boolean exists(String objectKey) {
            return client.doesObjectExist(bucket, objectKey);
        }

        @Override
        public void download(String objectKey, Path target, long maxBytes) throws Exception {
            try (InputStream input = client.getObject(bucket, objectKey).getObjectContent();
                 OutputStream output = Files.newOutputStream(target,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE)) {
                copyToLimited(input, output, maxBytes);
            }
        }

        @Override
        public void download(String objectKey, OutputStream output) throws Exception {
            try (InputStream input = client.getObject(bucket, objectKey).getObjectContent()) {
                copyToLimited(input, output, Long.MAX_VALUE);
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

    private static void copyToLimited(InputStream input, OutputStream output, long maxBytes) throws java.io.IOException {
        byte[] buffer = new byte[64 * 1024];
        long copied = 0L;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (copied > maxBytes - read) {
                throw new java.io.IOException("Object download exceeds configured byte limit");
            }
            output.write(buffer, 0, read);
            copied += read;
        }
    }

    static boolean isImmutableCreateConflict(String errorCode) {
        if (errorCode == null) {
            return false;
        }
        return "FileAlreadyExists".equalsIgnoreCase(errorCode)
                || "ObjectAlreadyExists".equalsIgnoreCase(errorCode)
                || "PreconditionFailed".equalsIgnoreCase(errorCode)
                || "ConditionalRequestConflict".equalsIgnoreCase(errorCode);
    }

    static boolean isIndeterminateImmutableCreateResponse(String errorCode) {
        return errorCode != null && "InvalidResponse".equalsIgnoreCase(errorCode);
    }

    private static boolean hasTextStatic(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public static final class ObjectInfo {
        private final long size;
        private final String etag;
        private final String versionId;
        private final Instant lastModified;

        public ObjectInfo(long size, String etag, String versionId, Instant lastModified) {
            this.size = size;
            this.etag = etag;
            this.versionId = versionId;
            this.lastModified = lastModified;
        }

        public long getSize() {
            return size;
        }

        public String getEtag() {
            return etag;
        }

        public String getVersionId() {
            return versionId;
        }

        public Instant getLastModified() {
            return lastModified;
        }
    }
}
