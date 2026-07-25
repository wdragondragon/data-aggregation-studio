package com.jdragon.studio.worker.idempotency;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.entity.RuntimeInvocationIdempotencyEntity;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.mapper.RuntimeInvocationIdempotencyMapper;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.infra.service.RuntimeInvocationFingerprintSupport;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/**
 * Shared-database idempotency guard for write invocations executed by a Worker.
 * Only opaque SHA-256 values and an encrypted response are persisted.
 */
@Service
public class RuntimeInvocationIdempotencyService {
    private static final int HARD_MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    private final RuntimeInvocationIdempotencyMapper mapper;
    private final EncryptionService encryptionService;
    private final ClusterInstanceIdentity instanceIdentity;
    private final StudioPlatformProperties properties;

    public RuntimeInvocationIdempotencyService(RuntimeInvocationIdempotencyMapper mapper,
                                               EncryptionService encryptionService,
                                               ClusterInstanceIdentity instanceIdentity,
                                               StudioPlatformProperties properties) {
        this.mapper = mapper;
        this.encryptionService = encryptionService;
        this.instanceIdentity = instanceIdentity;
        this.properties = properties;
    }

    public BeginResult begin(String tenantId,
                             Long projectId,
                             Long runtimeClusterId,
                             String resourceType,
                             Long resourceId,
                             String keyHash,
                             String requestFingerprint) {
        validateScope(tenantId, projectId, resourceType, resourceId);
        validateHash(keyHash, "idempotency key hash");
        validateHash(requestFingerprint, "request fingerprint");

        String ownerToken = UUID.randomUUID().toString();
        RuntimeInvocationIdempotencyEntity created = new RuntimeInvocationIdempotencyEntity();
        created.setId(IdWorker.getId());
        created.setTenantId(tenantId);
        created.setProjectId(projectId);
        created.setDeleted(Integer.valueOf(0));
        created.setCreatedAt(LocalDateTime.now());
        created.setUpdatedAt(created.getCreatedAt());
        created.setRuntimeClusterId(runtimeClusterId);
        created.setResourceType(resourceType);
        created.setResourceId(resourceId);
        created.setKeyHash(keyHash);
        created.setRequestFingerprint(requestFingerprint);
        created.setStatus(STATUS_RUNNING);
        created.setOwnerTokenHash(RuntimeInvocationFingerprintSupport.hashKey(ownerToken));
        created.setOwnerInstanceId(instanceIdentity.instanceId());
        created.setOwnerBootId(instanceIdentity.bootId());
        created.setVersion(Integer.valueOf(0));
        try {
            mapper.insert(created);
            return BeginResult.execute(created.getId(), ownerToken);
        } catch (DataAccessException duplicate) {
            RuntimeInvocationIdempotencyEntity existing = findExisting(
                    tenantId, projectId, resourceType, resourceId, keyHash);
            if (existing == null) {
                throw duplicate;
            }
            return existingResult(existing, requestFingerprint);
        }
    }

    public void complete(Long guardId, String ownerToken, int responseStatus,
                         String responseContentType, byte[] responseBody) {
        validateOwner(guardId, ownerToken);
        if (responseStatus < 100 || responseStatus > 599) {
            throw new IllegalArgumentException("Invalid idempotency response status");
        }
        byte[] body = responseBody == null ? new byte[0] : responseBody;
        int configuredLimit = properties.getRuntimeInvocationIdempotency() == null
                || properties.getRuntimeInvocationIdempotency().getMaxResponseBytes() == null
                ? 10 * 1024 * 1024
                : Math.min(HARD_MAX_RESPONSE_BYTES,
                        Math.max(1024, properties.getRuntimeInvocationIdempotency()
                                .getMaxResponseBytes().intValue()));
        if (body.length > configuredLimit) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "The idempotency replay payload exceeds the configured limit");
        }
        String encoded = Base64.getEncoder().encodeToString(body);
        String ciphertext = encryptionService.encrypt(encoded);
        LocalDateTime completedAt = LocalDateTime.now();
        int updated = mapper.update(null, new LambdaUpdateWrapper<RuntimeInvocationIdempotencyEntity>()
                .eq(RuntimeInvocationIdempotencyEntity::getId, guardId)
                .eq(RuntimeInvocationIdempotencyEntity::getStatus, STATUS_RUNNING)
                .eq(RuntimeInvocationIdempotencyEntity::getOwnerTokenHash,
                        RuntimeInvocationFingerprintSupport.hashKey(ownerToken))
                .eq(RuntimeInvocationIdempotencyEntity::getOwnerInstanceId, instanceIdentity.instanceId())
                .eq(RuntimeInvocationIdempotencyEntity::getOwnerBootId, instanceIdentity.bootId())
                .eq(RuntimeInvocationIdempotencyEntity::getVersion, Integer.valueOf(0))
                .set(RuntimeInvocationIdempotencyEntity::getStatus, STATUS_COMPLETED)
                .set(RuntimeInvocationIdempotencyEntity::getResponseStatus, Integer.valueOf(responseStatus))
                .set(RuntimeInvocationIdempotencyEntity::getResponseContentType, responseContentType)
                .set(RuntimeInvocationIdempotencyEntity::getResponseBodyCiphertext, ciphertext)
                .set(RuntimeInvocationIdempotencyEntity::getCompletedAt, completedAt)
                .set(RuntimeInvocationIdempotencyEntity::getUpdatedAt, completedAt)
                .set(RuntimeInvocationIdempotencyEntity::getVersion, Integer.valueOf(1)));
        if (updated != 1) {
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "The idempotency result could not be recorded");
        }
    }

    public void markUnknown(Long guardId, String ownerToken) {
        validateOwner(guardId, ownerToken);
        mapper.update(null, new LambdaUpdateWrapper<RuntimeInvocationIdempotencyEntity>()
                .eq(RuntimeInvocationIdempotencyEntity::getId, guardId)
                .eq(RuntimeInvocationIdempotencyEntity::getStatus, STATUS_RUNNING)
                .eq(RuntimeInvocationIdempotencyEntity::getOwnerTokenHash,
                        RuntimeInvocationFingerprintSupport.hashKey(ownerToken))
                .eq(RuntimeInvocationIdempotencyEntity::getOwnerInstanceId, instanceIdentity.instanceId())
                .eq(RuntimeInvocationIdempotencyEntity::getOwnerBootId, instanceIdentity.bootId())
                .eq(RuntimeInvocationIdempotencyEntity::getVersion, Integer.valueOf(0))
                .set(RuntimeInvocationIdempotencyEntity::getStatus, STATUS_UNKNOWN)
                .set(RuntimeInvocationIdempotencyEntity::getUpdatedAt, LocalDateTime.now())
                .set(RuntimeInvocationIdempotencyEntity::getVersion, Integer.valueOf(1)));
    }

    private BeginResult existingResult(RuntimeInvocationIdempotencyEntity existing,
                                       String requestFingerprint) {
        if (!requestFingerprint.equals(existing.getRequestFingerprint())) {
            return BeginResult.conflict(ConflictReason.FINGERPRINT_MISMATCH);
        }
        if (STATUS_COMPLETED.equals(existing.getStatus())) {
            if (existing.getResponseStatus() == null || existing.getResponseBodyCiphertext() == null) {
                tryMarkCompletedUnknown(existing);
                return BeginResult.conflict(ConflictReason.UNKNOWN);
            }
            try {
                String encoded = encryptionService.decrypt(existing.getResponseBodyCiphertext());
                byte[] body = Base64.getDecoder().decode(encoded);
                return BeginResult.replay(new StoredResponse(
                        existing.getResponseStatus().intValue(), existing.getResponseContentType(), body));
            } catch (RuntimeException ex) {
                tryMarkCompletedUnknown(existing);
                return BeginResult.conflict(ConflictReason.UNKNOWN);
            }
        }
        if (STATUS_RUNNING.equals(existing.getStatus())) {
            return BeginResult.conflict(ConflictReason.RUNNING);
        }
        return BeginResult.conflict(ConflictReason.UNKNOWN);
    }

    private void markCompletedUnknown(RuntimeInvocationIdempotencyEntity existing) {
        mapper.update(null, new LambdaUpdateWrapper<RuntimeInvocationIdempotencyEntity>()
                .eq(RuntimeInvocationIdempotencyEntity::getId, existing.getId())
                .eq(RuntimeInvocationIdempotencyEntity::getStatus, STATUS_COMPLETED)
                .eq(RuntimeInvocationIdempotencyEntity::getVersion, existing.getVersion())
                .set(RuntimeInvocationIdempotencyEntity::getStatus, STATUS_UNKNOWN)
                .set(RuntimeInvocationIdempotencyEntity::getResponseStatus, null)
                .set(RuntimeInvocationIdempotencyEntity::getResponseContentType, null)
                .set(RuntimeInvocationIdempotencyEntity::getResponseBodyCiphertext, null)
                .set(RuntimeInvocationIdempotencyEntity::getUpdatedAt, LocalDateTime.now())
                .set(RuntimeInvocationIdempotencyEntity::getVersion,
                        Integer.valueOf(existing.getVersion() == null ? 1 : existing.getVersion().intValue() + 1)));
    }

    private void tryMarkCompletedUnknown(RuntimeInvocationIdempotencyEntity existing) {
        try {
            markCompletedUnknown(existing);
        } catch (RuntimeException ignored) {
            // A corrupt replay must remain closed even when its status cannot be repaired immediately.
        }
    }

    private RuntimeInvocationIdempotencyEntity findExisting(String tenantId,
                                                             Long projectId,
                                                             String resourceType,
                                                             Long resourceId,
                                                             String keyHash) {
        return mapper.selectOne(new LambdaQueryWrapper<RuntimeInvocationIdempotencyEntity>()
                .eq(RuntimeInvocationIdempotencyEntity::getTenantId, tenantId)
                .eq(RuntimeInvocationIdempotencyEntity::getProjectId, projectId)
                .eq(RuntimeInvocationIdempotencyEntity::getResourceType, resourceType)
                .eq(RuntimeInvocationIdempotencyEntity::getResourceId, resourceId)
                .eq(RuntimeInvocationIdempotencyEntity::getKeyHash, keyHash)
                .last("limit 1"));
    }

    private void validateScope(String tenantId, Long projectId, String resourceType, Long resourceId) {
        if (tenantId == null || tenantId.trim().isEmpty()
                || projectId == null || resourceType == null || resourceType.trim().isEmpty()
                || resourceId == null) {
            throw new IllegalArgumentException("Complete runtime invocation scope is required");
        }
    }

    private void validateHash(String value, String label) {
        if (!RuntimeInvocationFingerprintSupport.isSha256(value)) {
            throw new IllegalArgumentException("Invalid " + label);
        }
    }

    private void validateOwner(Long guardId, String ownerToken) {
        if (guardId == null || ownerToken == null || ownerToken.isEmpty()) {
            throw new IllegalArgumentException("Complete idempotency guard ownership is required");
        }
    }

    public enum Action {
        EXECUTE,
        REPLAY,
        CONFLICT
    }

    public enum ConflictReason {
        FINGERPRINT_MISMATCH,
        RUNNING,
        UNKNOWN
    }

    public static final class BeginResult {
        private final Action action;
        private final Long guardId;
        private final String ownerToken;
        private final StoredResponse storedResponse;
        private final ConflictReason conflictReason;

        private BeginResult(Action action, Long guardId, String ownerToken, StoredResponse storedResponse,
                            ConflictReason conflictReason) {
            this.action = action;
            this.guardId = guardId;
            this.ownerToken = ownerToken;
            this.storedResponse = storedResponse;
            this.conflictReason = conflictReason;
        }

        public static BeginResult execute(Long guardId, String ownerToken) {
            return new BeginResult(Action.EXECUTE, guardId, ownerToken, null, null);
        }

        public static BeginResult replay(StoredResponse storedResponse) {
            return new BeginResult(Action.REPLAY, null, null, storedResponse, null);
        }

        public static BeginResult conflict(ConflictReason reason) {
            return new BeginResult(Action.CONFLICT, null, null, null, reason);
        }

        public Action getAction() {
            return action;
        }

        public Long getGuardId() {
            return guardId;
        }

        public String getOwnerToken() {
            return ownerToken;
        }

        public StoredResponse getStoredResponse() {
            return storedResponse;
        }

        public ConflictReason getConflictReason() {
            return conflictReason;
        }
    }

    public static final class StoredResponse {
        private final int status;
        private final String contentType;
        private final byte[] body;

        public StoredResponse(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body == null ? new byte[0] : Arrays.copyOf(body, body.length);
        }

        public int getStatus() {
            return status;
        }

        public String getContentType() {
            return contentType;
        }

        public byte[] getBody() {
            return Arrays.copyOf(body, body.length);
        }
    }
}
