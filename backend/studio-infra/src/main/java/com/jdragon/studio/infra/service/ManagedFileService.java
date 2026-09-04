package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.FieldComponentType;
import com.jdragon.studio.dto.enums.ManagedFileStatus;
import com.jdragon.studio.dto.model.ManagedFileAuditView;
import com.jdragon.studio.dto.model.ManagedFileMigrationIssueView;
import com.jdragon.studio.dto.model.ManagedFileReferenceView;
import com.jdragon.studio.dto.model.ManagedFileView;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.ManagedFileAuditEntity;
import com.jdragon.studio.infra.entity.ManagedFileEntity;
import com.jdragon.studio.infra.entity.ManagedFileLeaseEntity;
import com.jdragon.studio.infra.entity.ManagedFileReferenceEntity;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.mapper.ManagedFileAuditMapper;
import com.jdragon.studio.infra.mapper.ManagedFileLeaseMapper;
import com.jdragon.studio.infra.mapper.ManagedFileMapper;
import com.jdragon.studio.infra.mapper.ManagedFileReferenceMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ManagedFileService {

    public static final String URI_PREFIX = "managed-file://";
    public static final String OWNER_DATASOURCE = "DATASOURCE";
    private static final Pattern URI_PATTERN = Pattern.compile("^managed-file://([1-9][0-9]*)$");
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final ManagedFileMapper fileMapper;
    private final ManagedFileReferenceMapper referenceMapper;
    private final ManagedFileLeaseMapper leaseMapper;
    private final ManagedFileAuditMapper auditMapper;
    private final ManagedFilePolicyRegistry policyRegistry;
    private final ManagedFileCryptoService cryptoService;
    private final CloudObjectStorageService objectStorageService;
    private final StudioPlatformProperties properties;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final DatasourceMapper datasourceMapper;

    public ManagedFileService(ManagedFileMapper fileMapper,
                              ManagedFileReferenceMapper referenceMapper,
                              ManagedFileLeaseMapper leaseMapper,
                              ManagedFileAuditMapper auditMapper,
                              ManagedFilePolicyRegistry policyRegistry,
                              ManagedFileCryptoService cryptoService,
                              CloudObjectStorageService objectStorageService,
                              StudioPlatformProperties properties,
                              StudioSecurityService securityService,
                              ProjectResourceAccessService projectResourceAccessService,
                              DatasourceMapper datasourceMapper) {
        this.fileMapper = fileMapper;
        this.referenceMapper = referenceMapper;
        this.leaseMapper = leaseMapper;
        this.auditMapper = auditMapper;
        this.policyRegistry = policyRegistry;
        this.cryptoService = cryptoService;
        this.objectStorageService = objectStorageService;
        this.properties = properties;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.datasourceMapper = datasourceMapper;
    }

    public ManagedFileView upload(MultipartFile multipartFile, String policyCode) {
        requireEnabled();
        if (multipartFile == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "file is required");
        }
        ManagedFilePolicyRegistry.Policy policy = policyRegistry.require(policyCode);
        String fileName = policyRegistry.requireSafeFileName(multipartFile.getOriginalFilename());
        if (multipartFile.getSize() > policy.getMaxBytes()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Managed file exceeds policy limit of " + policy.getMaxBytes() + " bytes");
        }
        String tenantId = securityService.currentTenantId();
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        ManagedFileEntity entity = new ManagedFileEntity();
        entity.setTenantId(tenantId);
        entity.setProjectId(projectId);
        entity.setOriginalFileName(fileName);
        entity.setPolicyCode(normalizePolicy(policyCode));
        entity.setContentType(normalizeContentType(multipartFile.getContentType()));
        entity.setStatus(ManagedFileStatus.UPLOADING.name());
        entity.setEncryptionAlgorithm(ManagedFileCryptoService.ALGORITHM);
        entity.setEncryptionVersion(ManagedFileCryptoService.FORMAT_VERSION);
        entity.setUploadedBy(securityService.currentUserId());
        entity.setDeleteRetryCount(0);
        fileMapper.insert(entity);
        audit(entity, "UPLOAD_STARTED", "SUCCESS", null, null, null, null);

        Path stagingDir = null;
        Path plaintext = null;
        Path ciphertext = null;
        try {
            stagingDir = Files.createTempDirectory("studio-managed-upload-");
            plaintext = stagingDir.resolve("plaintext.bin");
            ciphertext = stagingDir.resolve("ciphertext.bin");
            try (InputStream input = multipartFile.getInputStream();
                 OutputStream output = Files.newOutputStream(plaintext,
                         StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                copyCapped(input, output, policy.getMaxBytes());
            }
            policyRegistry.validate(entity.getPolicyCode(), fileName, plaintext);
            ManagedFileCryptoService.EncryptionResult encrypted = cryptoService.encrypt(plaintext, ciphertext, entity);
            String objectKey = objectKey(entity, encrypted.getSha256());
            entity.setObjectBucket(objectStorageService.resolveBucket());
            entity.setObjectKey(objectKey);
            entity.setEncryptionIv(encrypted.getIvBase64());
            entity.setSha256(encrypted.getSha256());
            entity.setPlaintextSize(encrypted.getPlaintextSize());
            entity.setCiphertextSize(encrypted.getCiphertextSize());
            fileMapper.updateById(entity);
            boolean created = objectStorageService.putFileIfAbsent(entity.getObjectBucket(), objectKey,
                    ciphertext, "application/octet-stream");
            if (!created) {
                throw new IllegalStateException("Immutable managed file object already exists");
            }
            CloudObjectStorageService.ObjectInfo objectInfo = objectStorageService.stat(entity.getObjectBucket(), objectKey);
            if (objectInfo.getSize() != encrypted.getCiphertextSize()) {
                throw new IllegalStateException("Managed file object size verification failed");
            }
            entity.setStatus(ManagedFileStatus.READY.name());
            entity.setExpiresAt(LocalDateTime.now().plusHours(unboundRetentionHours()));
            entity.setErrorMessage(null);
            fileMapper.updateById(entity);
            audit(entity, "UPLOAD_COMPLETED", "SUCCESS", null, null, null,
                    "size=" + encrypted.getPlaintextSize());
            return toView(entity);
        } catch (Exception e) {
            markUploadFailed(entity, e);
            if (e instanceof StudioException) {
                throw (StudioException) e;
            }
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Managed file upload failed", e);
        } finally {
            deleteQuietly(plaintext);
            deleteQuietly(ciphertext);
            deleteQuietly(stagingDir);
        }
    }

    public PageView<ManagedFileView> queryPage(Integer pageNum, Integer pageSize,
                                               String policyCode, String status) {
        requireEnabled();
        int current = pageNum == null || pageNum.intValue() < 1 ? 1 : pageNum.intValue();
        int size = pageSize == null || pageSize.intValue() < 1 ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize.intValue(), MAX_PAGE_SIZE);
        String tenantId = securityService.currentTenantId();
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        LambdaQueryWrapper<ManagedFileEntity> query = new LambdaQueryWrapper<ManagedFileEntity>()
                .eq(ManagedFileEntity::getTenantId, tenantId)
                .eq(ManagedFileEntity::getProjectId, projectId)
                .ne(ManagedFileEntity::getStatus, ManagedFileStatus.DELETED.name());
        if (StringUtils.hasText(policyCode)) {
            policyRegistry.require(policyCode);
            query.eq(ManagedFileEntity::getPolicyCode, normalizePolicy(policyCode));
        }
        if (StringUtils.hasText(status)) {
            query.eq(ManagedFileEntity::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        query.orderByDesc(ManagedFileEntity::getCreatedAt);
        Page<ManagedFileEntity> page = fileMapper.selectPage(new Page<ManagedFileEntity>(current, size), query);
        Map<Long, Long> counts = referenceCounts(page.getRecords());
        List<ManagedFileView> views = new ArrayList<ManagedFileView>();
        for (ManagedFileEntity entity : page.getRecords()) {
            views.add(toView(entity, counts.getOrDefault(entity.getId(), 0L)));
        }
        return PageView.of(current, size, page.getTotal(), views);
    }

    public ManagedFileView get(Long id) {
        ManagedFileEntity entity = requireCurrentProjectFile(id, false);
        return toView(entity);
    }

    public List<ManagedFileReferenceView> references(Long id) {
        ManagedFileEntity entity = requireCurrentProjectFile(id, false);
        List<ManagedFileReferenceEntity> references = referenceMapper.selectList(
                new LambdaQueryWrapper<ManagedFileReferenceEntity>()
                        .eq(ManagedFileReferenceEntity::getTenantId, entity.getTenantId())
                        .eq(ManagedFileReferenceEntity::getProjectId, entity.getProjectId())
                        .eq(ManagedFileReferenceEntity::getFileId, id)
                        .orderByAsc(ManagedFileReferenceEntity::getOwnerType,
                                ManagedFileReferenceEntity::getOwnerId,
                                ManagedFileReferenceEntity::getFieldKey,
                                ManagedFileReferenceEntity::getOrdinal));
        List<ManagedFileReferenceView> result = new ArrayList<ManagedFileReferenceView>();
        for (ManagedFileReferenceEntity reference : references) {
            result.add(toReferenceView(reference));
        }
        return result;
    }

    public Download download(Long id) {
        requireDownloadPermission();
        ManagedFileEntity entity = requireCurrentProjectFile(id, true);
        Path staging = null;
        Path plaintext = null;
        try {
            staging = Files.createTempDirectory("studio-managed-download-");
            plaintext = staging.resolve(entity.getOriginalFileName());
            materialize(entity, plaintext);
            byte[] bytes = Files.readAllBytes(plaintext);
            audit(entity, "DOWNLOAD", "SUCCESS", null, null, null, null);
            return new Download(entity.getOriginalFileName(), entity.getContentType(), bytes);
        } catch (Exception e) {
            audit(entity, "DOWNLOAD", "FAILED", null, null, null, safeMessage(e));
            if (e instanceof StudioException) {
                throw (StudioException) e;
            }
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to download managed file", e);
        } finally {
            deleteQuietly(plaintext);
            deleteQuietly(staging);
        }
    }

    @Transactional
    public void requestDelete(Long id) {
        ManagedFileEntity entity = requireCurrentProjectFile(id, false);
        if (ManagedFileStatus.DELETED.name().equals(entity.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = fileMapper.markDeletePendingIfUnreferenced(
                entity.getId(), entity.getTenantId(), entity.getProjectId(), now);
        if (updated == 0) {
            List<ManagedFileReferenceView> activeReferences = references(id);
            if (!activeReferences.isEmpty()) {
                Set<String> summary = new LinkedHashSet<String>();
                for (ManagedFileReferenceView reference : activeReferences) {
                    summary.add(reference.getOwnerType() + ":" + reference.getOwnerId()
                            + ":" + reference.getFieldKey());
                }
                throw new StudioException(StudioErrorCode.CONFLICT,
                        "Managed file is still referenced by " + String.join(", ", summary));
            }
            throw new StudioException(StudioErrorCode.CONFLICT,
                    "Managed file state changed while requesting deletion: " + id);
        }
        entity.setStatus(ManagedFileStatus.DELETE_PENDING.name());
        entity.setNextDeleteAttemptAt(now);
        entity.setExpiresAt(now);
        audit(entity, "DELETE_REQUESTED", "SUCCESS", null, null, null, null);
    }

    public PageView<ManagedFileAuditView> queryAudits(Integer pageNum, Integer pageSize, Long fileId) {
        int current = pageNum == null || pageNum.intValue() < 1 ? 1 : pageNum.intValue();
        int size = pageSize == null || pageSize.intValue() < 1 ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize.intValue(), MAX_PAGE_SIZE);
        LambdaQueryWrapper<ManagedFileAuditEntity> query = new LambdaQueryWrapper<ManagedFileAuditEntity>()
                .eq(ManagedFileAuditEntity::getTenantId, securityService.currentTenantId())
                .eq(ManagedFileAuditEntity::getProjectId, projectResourceAccessService.requireCurrentProjectId());
        if (fileId != null) {
            query.eq(ManagedFileAuditEntity::getFileId, fileId);
        }
        query.orderByDesc(ManagedFileAuditEntity::getCreatedAt);
        Page<ManagedFileAuditEntity> page = auditMapper.selectPage(new Page<ManagedFileAuditEntity>(current, size), query);
        List<ManagedFileAuditView> views = new ArrayList<ManagedFileAuditView>();
        for (ManagedFileAuditEntity entity : page.getRecords()) {
            views.add(toAuditView(entity));
        }
        return PageView.of(current, size, page.getTotal(), views);
    }

    public List<ManagedFileMigrationIssueView> migrationIssues() {
        requireDownloadPermission();
        String tenantId = securityService.currentTenantId();
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        List<DatasourceEntity> datasources = datasourceMapper.selectList(
                new LambdaQueryWrapper<DatasourceEntity>()
                        .eq(DatasourceEntity::getTenantId, tenantId)
                        .eq(DatasourceEntity::getProjectId, projectId)
                        .in(DatasourceEntity::getTypeCode,
                                "kafka", "tbds-hdfs", "tbds-hdfs3", "tbds-hive3")
                        .orderByAsc(DatasourceEntity::getName));
        List<ManagedFileMigrationIssueView> result = new ArrayList<ManagedFileMigrationIssueView>();
        for (DatasourceEntity datasource : datasources) {
            List<String> fields = legacyManagedFields(datasource.getTypeCode(), datasource.getTechnicalMetadata());
            if (fields.isEmpty()) continue;
            ManagedFileMigrationIssueView issue = new ManagedFileMigrationIssueView();
            issue.setDatasourceId(datasource.getId());
            issue.setDatasourceName(datasource.getName());
            issue.setDatasourceTypeCode(datasource.getTypeCode());
            issue.setFieldKeys(fields);
            result.add(issue);
        }
        return result;
    }

    /** Validates managed-file metadata before the owning resource is persisted. */
    public void validateReferences(MetadataSchemaDefinition schema, Map<String, Object> metadata) {
        if (!managedProperties().isEnabled()) {
            return;
        }
        for (ManagedFieldValue value : collectManagedValues(schema, metadata)) {
            String tenantId = securityService.currentTenantId();
            Long projectId = projectResourceAccessService.requireCurrentProjectId();
            requireReadyFile(value.fileId, tenantId, projectId, value.policyCode);
            if (fileMapper.lockReadyForBinding(value.fileId, tenantId, projectId) == 0) {
                throw new StudioException(StudioErrorCode.CONFLICT,
                        "Managed file state changed while binding field " + value.fieldKey + ": " + value.fileId);
            }
        }
    }

    /** Replaces owner references in the caller's database transaction. */
    public void synchronizeReferences(String ownerType, Long ownerId,
                                       MetadataSchemaDefinition schema, Map<String, Object> metadata) {
        if (!managedProperties().isEnabled()) {
            return;
        }
        if (ownerId == null) {
            throw new IllegalArgumentException("ownerId is required");
        }
        String tenantId = securityService.currentTenantId();
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        List<ManagedFieldValue> desired = collectManagedValues(schema, metadata);
        LocalDateTime now = LocalDateTime.now();
        for (ManagedFieldValue value : desired) {
            pinReadyFileForBinding(value, tenantId, projectId, now);
        }
        List<ManagedFileReferenceEntity> existing = referenceMapper.selectList(
                ownerReferenceQuery(tenantId, projectId, ownerType, ownerId));
        referenceMapper.hardDeleteByOwner(tenantId, projectId, ownerType, ownerId);
        Set<Long> desiredIds = new LinkedHashSet<Long>();
        for (ManagedFieldValue value : desired) {
            ManagedFileReferenceEntity reference = new ManagedFileReferenceEntity();
            reference.setTenantId(tenantId);
            reference.setProjectId(projectId);
            reference.setFileId(value.fileId);
            reference.setOwnerType(ownerType);
            reference.setOwnerId(ownerId);
            reference.setFieldKey(value.fieldKey);
            reference.setOrdinal(value.ordinal);
            referenceMapper.insert(reference);
            desiredIds.add(value.fileId);
            audit(requireReadyFile(value.fileId, tenantId, projectId, value.policyCode),
                    "BOUND", "SUCCESS", ownerType, ownerId, value.fieldKey, null);
        }
        Set<Long> removedIds = new LinkedHashSet<Long>();
        for (ManagedFileReferenceEntity old : existing) {
            if (!desiredIds.contains(old.getFileId())) {
                removedIds.add(old.getFileId());
                ManagedFileEntity file = fileMapper.selectById(old.getFileId());
                if (file != null) {
                    audit(file, "UNBOUND", "SUCCESS", ownerType, ownerId, old.getFieldKey(), null);
                }
            }
        }
        scheduleUnreferencedFiles(removedIds, now);
    }

    public void removeOwnerReferences(String ownerType, Long ownerId, String tenantId, Long projectId) {
        if (!managedProperties().isEnabled()) {
            return;
        }
        List<ManagedFileReferenceEntity> existing = referenceMapper.selectList(
                ownerReferenceQuery(tenantId, projectId, ownerType, ownerId));
        referenceMapper.hardDeleteByOwner(tenantId, projectId, ownerType, ownerId);
        Set<Long> fileIds = new LinkedHashSet<Long>();
        for (ManagedFileReferenceEntity reference : existing) {
            fileIds.add(reference.getFileId());
            ManagedFileEntity file = fileMapper.selectById(reference.getFileId());
            if (file != null) {
                audit(file, "UNBOUND", "SUCCESS", ownerType, ownerId, reference.getFieldKey(), null);
            }
        }
        scheduleUnreferencedFiles(fileIds, LocalDateTime.now());
    }

    public ManagedFileEntity requireReadyFile(Long id, String tenantId, Long projectId, String expectedPolicy) {
        ManagedFileEntity entity = fileMapper.selectOne(new LambdaQueryWrapper<ManagedFileEntity>()
                .eq(ManagedFileEntity::getId, id)
                .eq(ManagedFileEntity::getTenantId, tenantId)
                .eq(ManagedFileEntity::getProjectId, projectId)
                .last("limit 1"));
        if (entity == null || !ManagedFileStatus.READY.name().equals(entity.getStatus())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Managed file is not ready: " + id);
        }
        if (StringUtils.hasText(expectedPolicy)
                && !normalizePolicy(expectedPolicy).equals(entity.getPolicyCode())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Managed file policy mismatch for file " + id + ": expected " + expectedPolicy);
        }
        return entity;
    }

    public void materialize(ManagedFileEntity entity, Path target) {
        if (entity == null || target == null) {
            throw new IllegalArgumentException("entity and target are required");
        }
        Path normalized = target.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        Path encrypted = null;
        Path plaintext = null;
        try {
            if (parent == null) {
                throw new IllegalArgumentException("target must have a parent directory");
            }
            Files.createDirectories(parent);
            encrypted = Files.createTempFile(parent, ".managed-encrypted-", ".tmp");
            Files.deleteIfExists(encrypted);
            plaintext = Files.createTempFile(parent, ".managed-plain-", ".tmp");
            Files.deleteIfExists(plaintext);
            long maxCiphertext = entity.getCiphertextSize() == null
                    ? policyRegistry.require(entity.getPolicyCode()).getMaxBytes() + 64L
                    : entity.getCiphertextSize().longValue();
            objectStorageService.downloadTo(entity.getObjectBucket(), entity.getObjectKey(), encrypted, maxCiphertext);
            if (entity.getCiphertextSize() == null || Files.size(encrypted) != entity.getCiphertextSize().longValue()) {
                throw new IllegalStateException("Managed file ciphertext size mismatch");
            }
            cryptoService.decrypt(encrypted, plaintext, entity);
            try {
                Files.move(plaintext, normalized, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(plaintext, normalized);
            }
            audit(entity, "RUNTIME_LOAD", "SUCCESS", null, null, null, null);
        } catch (Exception e) {
            audit(entity, "RUNTIME_LOAD", "FAILED", null, null, null, safeMessage(e));
            if (e instanceof StudioException) {
                throw (StudioException) e;
            }
            throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to materialize managed file", e);
        } finally {
            deleteQuietly(encrypted);
            deleteQuietly(plaintext);
        }
    }

    public LeaseRecord acquireLease(Long fileId, String tenantId, Long projectId,
                                    String consumerType, String consumerId, String workerInstanceId) {
        ManagedFileEntity file = requireReadyFile(fileId, tenantId, projectId, null);
        ManagedFileLeaseEntity lease = new ManagedFileLeaseEntity();
        lease.setTenantId(tenantId);
        lease.setProjectId(projectId);
        lease.setFileId(fileId);
        lease.setLeaseToken(UUID.randomUUID().toString());
        lease.setConsumerType(trimToLength(consumerType, 64));
        lease.setConsumerId(trimToLength(consumerId, 255));
        lease.setWorkerInstanceId(trimToLength(workerInstanceId, 255));
        lease.setHeartbeatAt(LocalDateTime.now());
        lease.setExpiresAt(LocalDateTime.now().plusSeconds(leaseTtlSeconds()));
        leaseMapper.insert(lease);
        audit(file, "LEASE_ACQUIRED", "SUCCESS", consumerType, null, null,
                "consumerId=" + trimToLength(consumerId, 128));
        return new LeaseRecord(lease.getId(), lease.getLeaseToken(), lease.getExpiresAt());
    }

    public LocalDateTime renewLease(String leaseToken) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusSeconds(leaseTtlSeconds());
        int changed = leaseMapper.update(null, new LambdaUpdateWrapper<ManagedFileLeaseEntity>()
                .eq(ManagedFileLeaseEntity::getLeaseToken, leaseToken)
                .isNull(ManagedFileLeaseEntity::getReleasedAt)
                .gt(ManagedFileLeaseEntity::getExpiresAt, now.minusSeconds(leaseTtlSeconds()))
                .set(ManagedFileLeaseEntity::getHeartbeatAt, now)
                .set(ManagedFileLeaseEntity::getExpiresAt, expiresAt));
        if (changed != 1) {
            throw new StudioException(StudioErrorCode.CONFLICT, "Managed file lease is no longer active");
        }
        return expiresAt;
    }

    public void releaseLease(String leaseToken) {
        if (!StringUtils.hasText(leaseToken)) {
            return;
        }
        leaseMapper.update(null, new LambdaUpdateWrapper<ManagedFileLeaseEntity>()
                .eq(ManagedFileLeaseEntity::getLeaseToken, leaseToken)
                .isNull(ManagedFileLeaseEntity::getReleasedAt)
                .set(ManagedFileLeaseEntity::getReleasedAt, LocalDateTime.now())
                .set(ManagedFileLeaseEntity::getExpiresAt, LocalDateTime.now()));
    }

    @Scheduled(fixedDelayString = "${studio.managed-file.gc-interval-millis:300000}")
    public void garbageCollect() {
        if (!managedProperties().isEnabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int batchSize = positive(managedProperties().getGcBatchSize(), 100);
        List<ManagedFileEntity> expired = fileMapper.selectList(new LambdaQueryWrapper<ManagedFileEntity>()
                .in(ManagedFileEntity::getStatus,
                        ManagedFileStatus.READY.name(), ManagedFileStatus.UPLOAD_FAILED.name())
                .isNotNull(ManagedFileEntity::getExpiresAt)
                .le(ManagedFileEntity::getExpiresAt, now)
                .last("limit " + batchSize));
        for (ManagedFileEntity file : expired) {
            if (referenceCount(file.getId()) == 0L) {
                fileMapper.markDeletePendingIfUnreferenced(
                        file.getId(), file.getTenantId(), file.getProjectId(), now);
            }
        }
        List<ManagedFileEntity> pending = fileMapper.selectList(new LambdaQueryWrapper<ManagedFileEntity>()
                .in(ManagedFileEntity::getStatus,
                        ManagedFileStatus.DELETE_PENDING.name(), ManagedFileStatus.DELETE_FAILED.name())
                .and(query -> query.isNull(ManagedFileEntity::getNextDeleteAttemptAt)
                        .or().le(ManagedFileEntity::getNextDeleteAttemptAt, now))
                .last("limit " + batchSize));
        for (ManagedFileEntity file : pending) {
            deleteObjectIfEligible(file, now);
        }
        LocalDateTime auditCutoff = now.minusDays(positive(managedProperties().getAuditRetentionDays(), 30));
        auditMapper.hardDeleteBefore(auditCutoff);
        leaseMapper.hardDeleteExpired(now.minusDays(1));
    }

    private ManagedFileEntity pinReadyFileForBinding(ManagedFieldValue value, String tenantId,
                                                      Long projectId, LocalDateTime now) {
        ManagedFileEntity file = requireReadyFile(value.fileId, tenantId, projectId, value.policyCode);
        int updated = fileMapper.update(null, new LambdaUpdateWrapper<ManagedFileEntity>()
                .eq(ManagedFileEntity::getId, value.fileId)
                .eq(ManagedFileEntity::getTenantId, tenantId)
                .eq(ManagedFileEntity::getProjectId, projectId)
                .eq(ManagedFileEntity::getStatus, ManagedFileStatus.READY.name())
                .set(ManagedFileEntity::getExpiresAt, null)
                .set(ManagedFileEntity::getBoundAt, now)
                .set(ManagedFileEntity::getLastReferencedAt, now));
        if (updated == 0) {
            throw new StudioException(StudioErrorCode.CONFLICT,
                    "Managed file state changed while binding field " + value.fieldKey + ": " + value.fileId);
        }
        return file;
    }

    public static Long parseManagedFileId(Object value, String fieldKey) {
        String text = value == null ? "" : String.valueOf(value).trim();
        Matcher matcher = URI_PATTERN.matcher(text);
        if (!matcher.matches()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Field " + fieldKey + " must use managed-file://<fileId>; legacy local paths must be re-uploaded");
        }
        try {
            return Long.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Invalid managed file reference in field " + fieldKey);
        }
    }

    public static boolean isManagedFileUri(Object value) {
        return value != null && URI_PATTERN.matcher(String.valueOf(value).trim()).matches();
    }

    private List<ManagedFieldValue> collectManagedValues(MetadataSchemaDefinition schema,
                                                         Map<String, Object> metadata) {
        if (schema == null || schema.getFields() == null) {
            return Collections.emptyList();
        }
        List<ManagedFieldValue> result = new ArrayList<ManagedFieldValue>();
        for (MetadataFieldDefinition field : schema.getFields()) {
            if (field.getComponentType() != FieldComponentType.MANAGED_FILE) {
                continue;
            }
            Object value = metadata == null ? null : metadata.get(field.getFieldKey());
            if (value == null || !StringUtils.hasText(String.valueOf(value))) {
                continue;
            }
            if (!StringUtils.hasText(field.getFilePolicyCode())) {
                throw new StudioException(StudioErrorCode.INTERNAL_SERVER_ERROR,
                        "Managed file field has no policy: " + field.getFieldKey());
            }
            if (value instanceof Iterable<?>) {
                int ordinal = 0;
                for (Object item : (Iterable<?>) value) {
                    if (item != null && StringUtils.hasText(String.valueOf(item))) {
                        result.add(new ManagedFieldValue(field.getFieldKey(), field.getFilePolicyCode(),
                                parseManagedFileId(item, field.getFieldKey()), ordinal));
                    }
                    ordinal++;
                }
            } else if (value.getClass().isArray()) {
                for (int ordinal = 0; ordinal < java.lang.reflect.Array.getLength(value); ordinal++) {
                    Object item = java.lang.reflect.Array.get(value, ordinal);
                    if (item != null && StringUtils.hasText(String.valueOf(item))) {
                        result.add(new ManagedFieldValue(field.getFieldKey(), field.getFilePolicyCode(),
                                parseManagedFileId(item, field.getFieldKey()), ordinal));
                    }
                }
            } else {
                result.add(new ManagedFieldValue(field.getFieldKey(), field.getFilePolicyCode(),
                        parseManagedFileId(value, field.getFieldKey()), 0));
            }
        }
        return result;
    }

    private List<String> legacyManagedFields(String typeCode, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return Collections.emptyList();
        String normalized = typeCode == null ? "" : typeCode.trim().toLowerCase(Locale.ROOT);
        List<String> candidates;
        if ("kafka".equals(normalized)) {
            candidates = List.of("kerberosKeytabFilePath", "krb5Conf");
        } else if ("tbds-hdfs".equals(normalized) || "tbds-hdfs3".equals(normalized)) {
            candidates = List.of("hdfsSiteFilePath", "coreSiteFilePath",
                    "kerberosKeytabFilePath", "krb5Conf");
        } else if ("tbds-hive3".equals(normalized)) {
            candidates = List.of("keytabPath", "krb5File");
        } else {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (String field : candidates) {
            Object value = metadata.get(field);
            if (value != null && StringUtils.hasText(String.valueOf(value)) && !isManagedFileUri(value)) {
                result.add(field);
            }
        }
        return result;
    }

    private void deleteObjectIfEligible(ManagedFileEntity file, LocalDateTime now) {
        if (referenceCount(file.getId()) > 0L || activeLeaseCount(file.getId(), now) > 0L) {
            return;
        }
        try {
            if (StringUtils.hasText(file.getObjectBucket()) && StringUtils.hasText(file.getObjectKey())
                    && objectStorageService.exists(file.getObjectBucket(), file.getObjectKey())) {
                objectStorageService.delete(file.getObjectBucket(), file.getObjectKey());
            }
            file.setStatus(ManagedFileStatus.DELETED.name());
            file.setDeletedAt(now);
            file.setNextDeleteAttemptAt(null);
            file.setErrorMessage(null);
            fileMapper.updateById(file);
            audit(file, "OBJECT_DELETED", "SUCCESS", null, null, null, null);
        } catch (Exception e) {
            int retry = file.getDeleteRetryCount() == null ? 1 : file.getDeleteRetryCount().intValue() + 1;
            file.setDeleteRetryCount(retry);
            file.setStatus(ManagedFileStatus.DELETE_FAILED.name());
            file.setErrorMessage(trimToLength(safeMessage(e), 1000));
            long delayMinutes = Math.min(24L * 60L, 1L << Math.min(retry, 10));
            file.setNextDeleteAttemptAt(now.plusMinutes(delayMinutes));
            fileMapper.updateById(file);
            audit(file, "OBJECT_DELETE", "FAILED", null, null, null, safeMessage(e));
        }
    }

    private void markUploadFailed(ManagedFileEntity entity, Exception e) {
        entity.setStatus(ManagedFileStatus.UPLOAD_FAILED.name());
        entity.setErrorMessage(trimToLength(safeMessage(e), 1000));
        entity.setExpiresAt(LocalDateTime.now());
        entity.setNextDeleteAttemptAt(LocalDateTime.now());
        fileMapper.updateById(entity);
        audit(entity, "UPLOAD_COMPLETED", "FAILED", null, null, null, safeMessage(e));
    }

    private void scheduleUnreferencedFiles(Set<Long> fileIds, LocalDateTime now) {
        for (Long fileId : fileIds) {
            if (fileId != null && referenceCount(fileId) == 0L) {
                fileMapper.update(null, new LambdaUpdateWrapper<ManagedFileEntity>()
                        .eq(ManagedFileEntity::getId, fileId)
                        .eq(ManagedFileEntity::getStatus, ManagedFileStatus.READY.name())
                        .set(ManagedFileEntity::getExpiresAt, now.plusHours(unboundRetentionHours()))
                        .set(ManagedFileEntity::getLastReferencedAt, now));
            }
        }
    }

    private LambdaQueryWrapper<ManagedFileReferenceEntity> ownerReferenceQuery(
            String tenantId, Long projectId, String ownerType, Long ownerId) {
        return new LambdaQueryWrapper<ManagedFileReferenceEntity>()
                .eq(ManagedFileReferenceEntity::getTenantId, tenantId)
                .eq(ManagedFileReferenceEntity::getProjectId, projectId)
                .eq(ManagedFileReferenceEntity::getOwnerType, ownerType)
                .eq(ManagedFileReferenceEntity::getOwnerId, ownerId);
    }

    private ManagedFileEntity requireCurrentProjectFile(Long id, boolean readyOnly) {
        if (id == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Managed file id is required");
        }
        ManagedFileEntity entity = fileMapper.selectOne(new LambdaQueryWrapper<ManagedFileEntity>()
                .eq(ManagedFileEntity::getId, id)
                .eq(ManagedFileEntity::getTenantId, securityService.currentTenantId())
                .eq(ManagedFileEntity::getProjectId, projectResourceAccessService.requireCurrentProjectId())
                .last("limit 1"));
        if (entity == null || ManagedFileStatus.DELETED.name().equals(entity.getStatus())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Managed file not found: " + id);
        }
        if (readyOnly && !ManagedFileStatus.READY.name().equals(entity.getStatus())) {
            throw new StudioException(StudioErrorCode.CONFLICT, "Managed file is not ready: " + id);
        }
        return entity;
    }

    private ManagedFileView toView(ManagedFileEntity entity) {
        return toView(entity, referenceCount(entity.getId()));
    }

    private ManagedFileView toView(ManagedFileEntity entity, long referenceCount) {
        ManagedFileView view = new ManagedFileView();
        view.setId(entity.getId());
        view.setFileName(entity.getOriginalFileName());
        view.setPolicyCode(entity.getPolicyCode());
        view.setContentType(entity.getContentType());
        view.setSizeBytes(entity.getPlaintextSize());
        view.setSha256(entity.getSha256());
        view.setSha256Summary(StringUtils.hasText(entity.getSha256()) && entity.getSha256().length() > 16
                ? entity.getSha256().substring(0, 16) : entity.getSha256());
        view.setStatus(entity.getStatus());
        view.setUploadedAt(entity.getCreatedAt());
        view.setExpiresAt(entity.getExpiresAt());
        view.setUploadedBy(entity.getUploadedBy());
        view.setReferenceCount(referenceCount);
        view.setReferenced(referenceCount > 0L);
        view.setDownloadable(canDownload() && ManagedFileStatus.READY.name().equals(entity.getStatus()));
        view.setDeletable(referenceCount == 0L && !ManagedFileStatus.DELETED.name().equals(entity.getStatus()));
        view.setErrorMessage(entity.getErrorMessage());
        return view;
    }

    private ManagedFileReferenceView toReferenceView(ManagedFileReferenceEntity entity) {
        ManagedFileReferenceView view = new ManagedFileReferenceView();
        view.setId(entity.getId());
        view.setFileId(entity.getFileId());
        view.setOwnerType(entity.getOwnerType());
        view.setOwnerId(entity.getOwnerId());
        view.setFieldKey(entity.getFieldKey());
        view.setOrdinal(entity.getOrdinal());
        view.setCreatedAt(entity.getCreatedAt());
        return view;
    }

    private ManagedFileAuditView toAuditView(ManagedFileAuditEntity entity) {
        ManagedFileAuditView view = new ManagedFileAuditView();
        view.setId(entity.getId());
        view.setFileId(entity.getFileId());
        view.setAction(entity.getAction());
        view.setOutcome(entity.getOutcome());
        view.setActorUserId(entity.getActorUserId());
        view.setActorName(entity.getActorName());
        view.setOwnerType(entity.getOwnerType());
        view.setOwnerId(entity.getOwnerId());
        view.setFieldKey(entity.getFieldKey());
        view.setDetail(entity.getDetail());
        view.setCreatedAt(entity.getCreatedAt());
        return view;
    }

    private Map<Long, Long> referenceCounts(List<ManagedFileEntity> files) {
        Map<Long, Long> result = new LinkedHashMap<Long, Long>();
        for (ManagedFileEntity file : files) {
            result.put(file.getId(), referenceCount(file.getId()));
        }
        return result;
    }

    private long referenceCount(Long fileId) {
        Long count = referenceMapper.selectCount(new LambdaQueryWrapper<ManagedFileReferenceEntity>()
                .eq(ManagedFileReferenceEntity::getFileId, fileId));
        return count == null ? 0L : count.longValue();
    }

    private long activeLeaseCount(Long fileId, LocalDateTime now) {
        Long count = leaseMapper.selectCount(new LambdaQueryWrapper<ManagedFileLeaseEntity>()
                .eq(ManagedFileLeaseEntity::getFileId, fileId)
                .isNull(ManagedFileLeaseEntity::getReleasedAt)
                .gt(ManagedFileLeaseEntity::getExpiresAt, now));
        return count == null ? 0L : count.longValue();
    }

    private void audit(ManagedFileEntity file, String action, String outcome,
                       String ownerType, Long ownerId, String fieldKey, String detail) {
        try {
            ManagedFileAuditEntity audit = new ManagedFileAuditEntity();
            audit.setTenantId(file.getTenantId());
            audit.setProjectId(file.getProjectId());
            audit.setFileId(file.getId());
            audit.setAction(action);
            audit.setOutcome(outcome);
            audit.setActorUserId(securityService.currentUserId());
            audit.setActorName(trimToLength(securityService.currentUsername(), 255));
            audit.setOwnerType(trimToLength(ownerType, 64));
            audit.setOwnerId(ownerId);
            audit.setFieldKey(trimToLength(fieldKey, 255));
            audit.setDetail(trimToLength(detail, 1000));
            auditMapper.insert(audit);
        } catch (Exception ignored) {
            // Auditing must not hide the primary storage or runtime result.
        }
    }

    private void requireDownloadPermission() {
        if (!canDownload()) {
            throw new StudioException(StudioErrorCode.FORBIDDEN,
                    "Managed file download requires project administrator permission");
        }
    }

    private boolean canDownload() {
        return securityService.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN,
                StudioConstants.ROLE_TENANT_ADMIN, StudioConstants.ROLE_PROJECT_ADMIN);
    }

    private void requireEnabled() {
        if (!managedProperties().isEnabled()) {
            throw new StudioException(StudioErrorCode.SERVICE_UNAVAILABLE,
                    "Managed file service is disabled");
        }
    }

    private StudioPlatformProperties.ManagedFileProperties managedProperties() {
        return properties.getManagedFile() == null
                ? new StudioPlatformProperties.ManagedFileProperties() : properties.getManagedFile();
    }

    public boolean isEnabled() {
        return managedProperties().isEnabled();
    }

    private int unboundRetentionHours() {
        return positive(managedProperties().getUnboundRetentionHours(), 24);
    }

    private int leaseTtlSeconds() {
        return positive(managedProperties().getLeaseTtlSeconds(), 300);
    }

    private int positive(Integer value, int fallback) {
        return value == null || value.intValue() <= 0 ? fallback : value.intValue();
    }

    private String objectKey(ManagedFileEntity entity, String sha256) {
        String prefix = trimSlashes(managedProperties().getObjectPrefix());
        return prefix + "/" + tenantHash(entity.getTenantId()) + "/" + entity.getProjectId()
                + "/" + entity.getId() + "/" + sha256 + ".bin";
    }

    private String tenantHash(String tenantId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(String.valueOf(tenantId).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(16);
            for (int index = 0; index < 8; index++) {
                result.append(Character.forDigit((bytes[index] >>> 4) & 0x0f, 16));
                result.append(Character.forDigit(bytes[index] & 0x0f, 16));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash tenant id", e);
        }
    }

    private long copyCapped(InputStream input, OutputStream output, long maxBytes) throws Exception {
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            total += read;
            if (total > maxBytes) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "Managed file exceeds policy limit of " + maxBytes + " bytes");
            }
            output.write(buffer, 0, read);
        }
        return total;
    }

    private String normalizePolicy(String value) {
        policyRegistry.require(value);
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeContentType(String contentType) {
        return StringUtils.hasText(contentType) ? trimToLength(contentType.trim(), 255) : "application/octet-stream";
    }

    private String trimSlashes(String value) {
        String result = StringUtils.hasText(value) ? value.trim().replace('\\', '/') : "studio/managed-files";
        while (result.startsWith("/")) result = result.substring(1);
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result.isEmpty() ? "studio/managed-files" : result;
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null) return null;
        String message = throwable.getMessage();
        return StringUtils.hasText(message) ? message : throwable.getClass().getSimpleName();
    }

    private String trimToLength(String value, int maxLength) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // Temporary staging has no business identity and can be cleaned by the host.
        }
    }

    private static final class ManagedFieldValue {
        private final String fieldKey;
        private final String policyCode;
        private final Long fileId;
        private final int ordinal;

        private ManagedFieldValue(String fieldKey, String policyCode, Long fileId, int ordinal) {
            this.fieldKey = fieldKey;
            this.policyCode = policyCode;
            this.fileId = fileId;
            this.ordinal = ordinal;
        }
    }

    public static final class Download {
        private final String fileName;
        private final String contentType;
        private final byte[] bytes;

        public Download(String fileName, String contentType, byte[] bytes) {
            this.fileName = fileName;
            this.contentType = contentType;
            this.bytes = bytes;
        }

        public String getFileName() { return fileName; }
        public String getContentType() { return contentType; }
        public byte[] getBytes() { return bytes; }
    }

    public static final class LeaseRecord {
        private final Long id;
        private final String token;
        private final LocalDateTime expiresAt;

        public LeaseRecord(Long id, String token, LocalDateTime expiresAt) {
            this.id = id;
            this.token = token;
            this.expiresAt = expiresAt;
        }

        public Long getId() { return id; }
        public String getToken() { return token; }
        public LocalDateTime getExpiresAt() { return expiresAt; }
    }
}
