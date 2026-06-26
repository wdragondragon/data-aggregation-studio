package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.EnvironmentDependencyFileView;
import com.jdragon.studio.dto.model.EnvironmentDependencyOptionView;
import com.jdragon.studio.dto.model.EnvironmentDependencyView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.EnvironmentDependencySaveRequest;
import com.jdragon.studio.infra.entity.EnvironmentDependencyEntity;
import com.jdragon.studio.infra.entity.EnvironmentDependencyFileEntity;
import com.jdragon.studio.infra.entity.ScriptEnvironmentDependencyRelEntity;
import com.jdragon.studio.infra.entity.ScriptEnvironmentEntity;
import com.jdragon.studio.infra.mapper.EnvironmentDependencyFileMapper;
import com.jdragon.studio.infra.mapper.EnvironmentDependencyMapper;
import com.jdragon.studio.infra.mapper.ScriptEnvironmentDependencyRelMapper;
import com.jdragon.studio.infra.mapper.ScriptEnvironmentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class EnvironmentDependencyService {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentDependencyService.class);
    private static final String DEFAULT_SCRIPT_TYPE = ScriptType.JAVA.name();
    private static final String OSS_PREFIX = "oss://";
    private static final String MANAGED_OBJECT_PREFIX = "studio/env/";

    private final EnvironmentDependencyMapper dependencyMapper;
    private final EnvironmentDependencyFileMapper dependencyFileMapper;
    private final ScriptEnvironmentDependencyRelMapper relationMapper;
    private final ScriptEnvironmentMapper environmentMapper;
    private final StudioSecurityService securityService;
    private final ObjectProvider<ScriptEnvironmentRuntimeService> runtimeServiceProvider;
    private final CloudObjectStorageService cloudObjectStorageService;

    public EnvironmentDependencyService(EnvironmentDependencyMapper dependencyMapper,
                                         EnvironmentDependencyFileMapper dependencyFileMapper,
                                         ScriptEnvironmentDependencyRelMapper relationMapper,
                                         ScriptEnvironmentMapper environmentMapper,
                                         StudioSecurityService securityService,
                                         ObjectProvider<ScriptEnvironmentRuntimeService> runtimeServiceProvider,
                                         CloudObjectStorageService cloudObjectStorageService) {
        this.dependencyMapper = dependencyMapper;
        this.dependencyFileMapper = dependencyFileMapper;
        this.relationMapper = relationMapper;
        this.environmentMapper = environmentMapper;
        this.securityService = securityService;
        this.runtimeServiceProvider = runtimeServiceProvider;
        this.cloudObjectStorageService = cloudObjectStorageService;
    }

    public PageView<EnvironmentDependencyView> queryPage(Integer pageNum, Integer pageSize, String keyword, Boolean enabled) {
        int safePageNo = normalizePageNo(pageNum);
        int safePageSize = normalizePageSize(pageSize);
        String normalizedKeyword = normalizeNullableText(keyword);
        Page<EnvironmentDependencyEntity> page = new Page<EnvironmentDependencyEntity>(safePageNo, safePageSize);
        LambdaQueryWrapper<EnvironmentDependencyEntity> wrapper = new LambdaQueryWrapper<EnvironmentDependencyEntity>()
                .eq(EnvironmentDependencyEntity::getTenantId, securityService.currentTenantId())
                .and(hasText(normalizedKeyword), query -> query.like(EnvironmentDependencyEntity::getName, normalizedKeyword)
                        .or()
                        .like(EnvironmentDependencyEntity::getVersion, normalizedKeyword)
                        .or()
                        .like(EnvironmentDependencyEntity::getScriptType, normalizedKeyword)
                        .or()
                        .like(EnvironmentDependencyEntity::getDescription, normalizedKeyword))
                .eq(enabled != null, EnvironmentDependencyEntity::getEnabled, Boolean.TRUE.equals(enabled) ? Integer.valueOf(1) : Integer.valueOf(0))
                .orderByDesc(EnvironmentDependencyEntity::getUpdatedAt)
                .orderByDesc(EnvironmentDependencyEntity::getId);
        Page<EnvironmentDependencyEntity> entityPage = dependencyMapper.selectPage(page, wrapper);
        List<EnvironmentDependencyView> items = new ArrayList<EnvironmentDependencyView>();
        for (EnvironmentDependencyEntity entity : entityPage.getRecords()) {
            items.add(toView(entity));
        }
        return PageView.of(safePageNo, safePageSize, entityPage.getTotal(), items);
    }

    public List<EnvironmentDependencyOptionView> options(Boolean enabledOnly) {
        LambdaQueryWrapper<EnvironmentDependencyEntity> wrapper = new LambdaQueryWrapper<EnvironmentDependencyEntity>()
                .eq(EnvironmentDependencyEntity::getTenantId, securityService.currentTenantId())
                .eq(Boolean.TRUE.equals(enabledOnly), EnvironmentDependencyEntity::getEnabled, Integer.valueOf(1))
                .orderByAsc(EnvironmentDependencyEntity::getScriptType)
                .orderByAsc(EnvironmentDependencyEntity::getName)
                .orderByAsc(EnvironmentDependencyEntity::getVersion)
                .orderByAsc(EnvironmentDependencyEntity::getId);
        List<EnvironmentDependencyOptionView> result = new ArrayList<EnvironmentDependencyOptionView>();
        for (EnvironmentDependencyEntity entity : dependencyMapper.selectList(wrapper)) {
            result.add(toOptionView(entity));
        }
        return result;
    }

    public EnvironmentDependencyView get(Long id) {
        return toView(requireEntity(id));
    }

    @Transactional
    public EnvironmentDependencyView saveOrUpdateCheck(EnvironmentDependencySaveRequest request) {
        requireManager();
        validateRequest(request);
        EnvironmentDependencyEntity entity = request.getId() == null
                ? new EnvironmentDependencyEntity()
                : requireEntity(request.getId());
        ensureUniqueNameVersion(normalizeText(request.getName()), normalizeNullableText(request.getVersion()), entity.getId());
        entity.setTenantId(securityService.currentTenantId());
        entity.setName(normalizeText(request.getName()));
        entity.setVersion(normalizeNullableText(request.getVersion()));
        entity.setScriptType(normalizeScriptType(request.getScriptType()));
        entity.setArtifactUrl(normalizeText(request.getArtifactUrl()));
        entity.setArtifactType(normalizeArtifactType(request.getArtifactType()));
        entity.setChecksum(normalizeNullableText(request.getChecksum()));
        entity.setEnabled(Boolean.FALSE.equals(request.getEnabled()) ? Integer.valueOf(0) : Integer.valueOf(1));
        entity.setDescription(normalizeNullableText(request.getDescription()));
        if (entity.getId() == null) {
            dependencyMapper.insert(entity);
        } else {
            dependencyMapper.updateById(entity);
        }
        invalidateReferencingEnvironments(entity.getId());
        return get(entity.getId());
    }

    @Transactional
    public EnvironmentDependencyView saveOrUpdateCheck(Long id,
                                                       String name,
                                                       String version,
                                                       String scriptType,
                                                       Boolean enabled,
                                                       String description,
                                                       MultipartFile file) {
        List<MultipartFile> files = new ArrayList<MultipartFile>();
        if (file != null) {
            files.add(file);
        }
        return saveOrUpdateCheck(id, name, version, scriptType, enabled, description, files);
    }

    @Transactional
    public EnvironmentDependencyView saveOrUpdateCheck(Long id,
                                                       String name,
                                                       String version,
                                                       String scriptType,
                                                       Boolean enabled,
                                                       String description,
                                                       List<MultipartFile> files) {
        requireManager();
        if (!hasText(name)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Dependency name is required");
        }
        EnvironmentDependencyEntity entity = id == null ? new EnvironmentDependencyEntity() : requireEntity(id);
        ensureUniqueNameVersion(normalizeText(name), normalizeNullableText(version), entity.getId());
        List<MultipartFile> uploadFiles = normalizeUploadFiles(files);
        if (entity.getId() == null && uploadFiles.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Dependency file is required");
        }

        String normalizedScriptType = normalizeScriptType(scriptType);
        entity.setTenantId(securityService.currentTenantId());
        entity.setName(normalizeText(name));
        entity.setVersion(normalizeNullableText(version));
        entity.setScriptType(normalizedScriptType);
        entity.setEnabled(Boolean.FALSE.equals(enabled) ? Integer.valueOf(0) : Integer.valueOf(1));
        entity.setDescription(normalizeNullableText(description));
        if (entity.getId() == null) {
            dependencyMapper.insert(entity);
        } else {
            dependencyMapper.updateById(entity);
        }

        if (!uploadFiles.isEmpty()) {
            List<EnvironmentDependencyFileEntity> existingFiles = listAllFiles(entity.getId());
            List<PreparedUpload> preparedUploads = prepareUploads(entity, uploadFiles);
            uploadFilesForDependency(entity, preparedUploads);
            restoreUnchangedFiles(entity.getId(), existingFiles, uploadedVisibleNames(preparedUploads));
            refreshLegacyArtifactColumns(entity.getId());
        }
        invalidateReferencingEnvironments(entity.getId());
        return get(entity.getId());
    }

    @Transactional
    public EnvironmentDependencyView enable(Long id) {
        return updateEnabled(id, true);
    }

    @Transactional
    public EnvironmentDependencyView disable(Long id) {
        return updateEnabled(id, false);
    }

    @Transactional
    public void delete(Long id) {
        requireManager();
        EnvironmentDependencyEntity entity = requireEntity(id);
        for (EnvironmentDependencyFileEntity file : listAllFiles(entity.getId())) {
            deleteObjectQuietly(file);
        }
        dependencyFileMapper.delete(new LambdaQueryWrapper<EnvironmentDependencyFileEntity>()
                .eq(EnvironmentDependencyFileEntity::getTenantId, securityService.currentTenantId())
                .eq(EnvironmentDependencyFileEntity::getDependencyId, entity.getId()));
        dependencyMapper.deleteById(entity.getId());
        deleteManagedArtifactQuietly(entity.getArtifactUrl());
        invalidateReferencingEnvironments(entity.getId());
    }

    public DependencyFileDownload downloadFile(Long dependencyId, Long fileId) {
        EnvironmentDependencyEntity dependency = requireEntity(dependencyId);
        EnvironmentDependencyFileEntity file = requireVisibleFile(dependency.getId(), fileId);
        OssArtifact artifact = resolveFileArtifact(file);
        if (artifact == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Dependency file object is missing");
        }
        try {
            byte[] bytes = cloudObjectStorageService.get(artifact.bucket, artifact.objectKey);
            return new DependencyFileDownload(file.getOriginalFileName(), contentType(file.getArtifactType()), bytes);
        } catch (RuntimeException ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Failed to download dependency file: " + rootMessage(ex), ex);
        }
    }

    @Transactional
    public void deleteFile(Long dependencyId, Long fileId) {
        requireManager();
        EnvironmentDependencyEntity dependency = requireEntity(dependencyId);
        EnvironmentDependencyFileEntity file = requireVisibleFile(dependency.getId(), fileId);
        deleteObjectQuietly(file);
        deleteExtractedArtifacts(file.getId());
        dependencyFileMapper.deleteById(file.getId());
        refreshLegacyArtifactColumns(dependency.getId());
        invalidateReferencingEnvironments(dependency.getId());
    }

    EnvironmentDependencyEntity requireEnabledDependency(Long id) {
        EnvironmentDependencyEntity entity = requireEntity(id);
        if (entity.getEnabled() == null || entity.getEnabled().intValue() != 1) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Environment dependency is disabled: " + id);
        }
        return entity;
    }

    List<EnvironmentDependencyFileEntity> listRuntimeArtifacts(Long dependencyId) {
        if (dependencyId == null) {
            return new ArrayList<EnvironmentDependencyFileEntity>();
        }
        return dependencyFileMapper.selectList(new LambdaQueryWrapper<EnvironmentDependencyFileEntity>()
                .eq(EnvironmentDependencyFileEntity::getTenantId, securityService.currentTenantId())
                .eq(EnvironmentDependencyFileEntity::getDependencyId, dependencyId)
                .eq(EnvironmentDependencyFileEntity::getEnabled, Integer.valueOf(1))
                .eq(EnvironmentDependencyFileEntity::getRuntimeArtifact, Integer.valueOf(1))
                .eq(EnvironmentDependencyFileEntity::getArtifactType, "JAR")
                .orderByAsc(EnvironmentDependencyFileEntity::getVisible)
                .orderByAsc(EnvironmentDependencyFileEntity::getOriginalFileName)
                .orderByAsc(EnvironmentDependencyFileEntity::getId));
    }

    EnvironmentDependencyView toView(EnvironmentDependencyEntity entity) {
        EnvironmentDependencyView view = new EnvironmentDependencyView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setName(entity.getName());
        view.setVersion(entity.getVersion());
        view.setScriptType(hasText(entity.getScriptType()) ? entity.getScriptType() : DEFAULT_SCRIPT_TYPE);
        view.setArtifactUrl(entity.getArtifactUrl());
        view.setArtifactType(entity.getArtifactType());
        view.setChecksum(entity.getChecksum());
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled().intValue() == 1);
        view.setDescription(entity.getDescription());
        List<EnvironmentDependencyFileView> fileViews = toFileViews(listVisibleFiles(entity.getId()));
        if (fileViews.isEmpty() && hasText(entity.getArtifactUrl())) {
            fileViews.add(toLegacyFileView(entity));
        }
        view.setFiles(fileViews);
        return view;
    }

    EnvironmentDependencyView toReferenceView(EnvironmentDependencyEntity entity) {
        EnvironmentDependencyView view = new EnvironmentDependencyView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setName(entity.getName());
        view.setVersion(entity.getVersion());
        view.setScriptType(hasText(entity.getScriptType()) ? entity.getScriptType() : DEFAULT_SCRIPT_TYPE);
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled().intValue() == 1);
        view.setDescription(entity.getDescription());
        return view;
    }

    private EnvironmentDependencyOptionView toOptionView(EnvironmentDependencyEntity entity) {
        EnvironmentDependencyOptionView view = new EnvironmentDependencyOptionView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setName(entity.getName());
        view.setVersion(entity.getVersion());
        view.setScriptType(hasText(entity.getScriptType()) ? entity.getScriptType() : DEFAULT_SCRIPT_TYPE);
        view.setEnabled(entity.getEnabled() != null && entity.getEnabled().intValue() == 1);
        return view;
    }

    private List<PreparedUpload> prepareUploads(EnvironmentDependencyEntity dependency, List<MultipartFile> files) {
        List<PreparedUpload> result = new ArrayList<PreparedUpload>();
        for (MultipartFile file : files) {
            result.add(prepareUpload(dependency, file));
        }
        return result;
    }

    private void uploadFilesForDependency(EnvironmentDependencyEntity dependency, List<PreparedUpload> uploads) {
        for (PreparedUpload upload : uploads) {
            persistPreparedUpload(dependency, upload);
        }
    }

    private Set<String> uploadedVisibleNames(List<PreparedUpload> uploads) {
        Set<String> names = new HashSet<String>();
        for (PreparedUpload upload : uploads) {
            names.add(upload.originalFileName);
        }
        return names;
    }

    private void restoreUnchangedFiles(Long dependencyId,
                                       List<EnvironmentDependencyFileEntity> previousFiles,
                                       Set<String> uploadedVisibleNames) {
        if (previousFiles == null || previousFiles.isEmpty()) {
            return;
        }
        List<EnvironmentDependencyFileEntity> currentFiles = listAllFiles(dependencyId);
        Set<Long> currentIds = new HashSet<Long>();
        for (EnvironmentDependencyFileEntity currentFile : currentFiles) {
            currentIds.add(currentFile.getId());
        }

        Map<Long, EnvironmentDependencyFileEntity> previousById = new HashMap<Long, EnvironmentDependencyFileEntity>();
        Map<Long, Long> restoredSourceIds = new HashMap<Long, Long>();
        for (EnvironmentDependencyFileEntity previousFile : previousFiles) {
            previousById.put(previousFile.getId(), previousFile);
        }

        for (EnvironmentDependencyFileEntity previousFile : previousFiles) {
            if (currentIds.contains(previousFile.getId())
                    || previousFile.getVisible() == null
                    || previousFile.getVisible().intValue() != 1
                    || uploadedVisibleNames.contains(previousFile.getOriginalFileName())) {
                continue;
            }
            EnvironmentDependencyFileEntity restored = copyFile(previousFile);
            dependencyFileMapper.insert(restored);
            restoredSourceIds.put(previousFile.getId(), restored.getId());
        }

        for (EnvironmentDependencyFileEntity previousFile : previousFiles) {
            if (currentIds.contains(previousFile.getId())
                    || previousFile.getVisible() == null
                    || previousFile.getVisible().intValue() != 0) {
                continue;
            }
            EnvironmentDependencyFileEntity previousSource = previousById.get(previousFile.getSourceFileId());
            if (previousSource != null && uploadedVisibleNames.contains(previousSource.getOriginalFileName())) {
                continue;
            }
            EnvironmentDependencyFileEntity restored = copyFile(previousFile);
            Long restoredSourceId = restoredSourceIds.get(previousFile.getSourceFileId());
            if (restoredSourceId != null) {
                restored.setSourceFileId(restoredSourceId);
            }
            dependencyFileMapper.insert(restored);
        }
    }

    private EnvironmentDependencyFileEntity copyFile(EnvironmentDependencyFileEntity source) {
        EnvironmentDependencyFileEntity target = new EnvironmentDependencyFileEntity();
        target.setTenantId(source.getTenantId());
        target.setDependencyId(source.getDependencyId());
        target.setOriginalFileName(source.getOriginalFileName());
        target.setArtifactType(source.getArtifactType());
        target.setObjectKey(source.getObjectKey());
        target.setObjectUrl(source.getObjectUrl());
        target.setChecksum(source.getChecksum());
        target.setSizeBytes(source.getSizeBytes());
        target.setVisible(source.getVisible());
        target.setRuntimeArtifact(source.getRuntimeArtifact());
        target.setSourceFileId(source.getSourceFileId());
        target.setEnabled(source.getEnabled());
        return target;
    }

    private PreparedUpload prepareUpload(EnvironmentDependencyEntity dependency, MultipartFile file) {
        try {
            String originalName = normalizeOriginalFilename(file.getOriginalFilename());
            String artifactType = inferArtifactType(originalName);
            byte[] bytes;
            try (InputStream inputStream = file.getInputStream()) {
                bytes = StreamUtils.copyToByteArray(inputStream);
            }
            if (bytes.length == 0) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Dependency file is empty: " + originalName);
            }
            List<PreparedRuntimeJar> runtimeJars = new ArrayList<PreparedRuntimeJar>();
            if ("ZIP".equals(artifactType)) {
                runtimeJars = extractRuntimeJars(dependency, originalName, bytes);
            }
            return new PreparedUpload(originalName, artifactType, bytes, sha256(bytes), runtimeJars);
        } catch (StudioException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Failed to read dependency file: " + rootMessage(ex), ex);
        }
    }

    private void persistPreparedUpload(EnvironmentDependencyEntity dependency, PreparedUpload upload) {
        String bucket = cloudObjectStorageService.resolveBucket();
        String originalObjectKey = buildUploadedObjectKey(dependency.getScriptType(), dependency.getId(), upload.originalFileName);
        cloudObjectStorageService.put(bucket, originalObjectKey, upload.bytes, contentType(upload.artifactType));

        EnvironmentDependencyFileEntity visibleFile = upsertVisibleFile(dependency, upload, originalObjectKey, bucket);
        if ("JAR".equals(upload.artifactType)) {
            deleteExtractedArtifacts(visibleFile.getId());
            return;
        }

        Set<String> extractedNames = new LinkedHashSet<String>();
        for (PreparedRuntimeJar runtimeJar : upload.runtimeJars) {
            cloudObjectStorageService.put(bucket, runtimeJar.objectKey, runtimeJar.bytes, contentType("JAR"));
            upsertExtractedRuntimeJar(dependency, visibleFile.getId(), runtimeJar, bucket);
            extractedNames.add(runtimeJar.originalFileName);
        }
        deleteStaleExtractedArtifacts(visibleFile.getId(), extractedNames);
    }

    private EnvironmentDependencyFileEntity upsertVisibleFile(EnvironmentDependencyEntity dependency,
                                                             PreparedUpload upload,
                                                             String objectKey,
                                                             String bucket) {
        EnvironmentDependencyFileEntity file = findVisibleFile(dependency.getId(), upload.originalFileName);
        if (file == null) {
            file = new EnvironmentDependencyFileEntity();
            file.setTenantId(securityService.currentTenantId());
            file.setDependencyId(dependency.getId());
            file.setOriginalFileName(upload.originalFileName);
        }
        file.setArtifactType(upload.artifactType);
        file.setObjectKey(objectKey);
        file.setObjectUrl(OSS_PREFIX + bucket + "/" + objectKey);
        file.setChecksum(upload.checksum);
        file.setSizeBytes(Long.valueOf(upload.bytes.length));
        file.setVisible(Integer.valueOf(1));
        file.setRuntimeArtifact("JAR".equals(upload.artifactType) ? Integer.valueOf(1) : Integer.valueOf(0));
        file.setSourceFileId(null);
        file.setEnabled(Integer.valueOf(1));
        if (file.getId() == null) {
            dependencyFileMapper.insert(file);
        } else {
            dependencyFileMapper.updateById(file);
        }
        return file;
    }

    private void upsertExtractedRuntimeJar(EnvironmentDependencyEntity dependency,
                                           Long sourceFileId,
                                           PreparedRuntimeJar runtimeJar,
                                           String bucket) {
        EnvironmentDependencyFileEntity file = findExtractedFile(sourceFileId, runtimeJar.originalFileName);
        if (file == null) {
            file = new EnvironmentDependencyFileEntity();
            file.setTenantId(securityService.currentTenantId());
            file.setDependencyId(dependency.getId());
            file.setOriginalFileName(runtimeJar.originalFileName);
            file.setSourceFileId(sourceFileId);
        }
        file.setArtifactType("JAR");
        file.setObjectKey(runtimeJar.objectKey);
        file.setObjectUrl(OSS_PREFIX + bucket + "/" + runtimeJar.objectKey);
        file.setChecksum(runtimeJar.checksum);
        file.setSizeBytes(Long.valueOf(runtimeJar.bytes.length));
        file.setVisible(Integer.valueOf(0));
        file.setRuntimeArtifact(Integer.valueOf(1));
        file.setEnabled(Integer.valueOf(1));
        if (file.getId() == null) {
            dependencyFileMapper.insert(file);
        } else {
            dependencyFileMapper.updateById(file);
        }
    }

    private List<PreparedRuntimeJar> extractRuntimeJars(EnvironmentDependencyEntity dependency,
                                                        String zipFileName,
                                                        byte[] bytes) throws Exception {
        List<PreparedRuntimeJar> jars = new ArrayList<PreparedRuntimeJar>();
        try (ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry = zipInputStream.getNextEntry();
            while (entry != null) {
                if (!entry.isDirectory()
                        && entry.getName() != null
                        && entry.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    String entryName = normalizeZipJarEntryName(entry.getName());
                    byte[] jarBytes = StreamUtils.copyToByteArray(zipInputStream);
                    if (jarBytes.length == 0) {
                        throw new StudioException(StudioErrorCode.BAD_REQUEST, "ZIP dependency contains empty JAR entry: " + entryName);
                    }
                    String objectKey = buildExtractedObjectKey(dependency.getScriptType(), dependency.getId(), zipFileName, entryName);
                    jars.add(new PreparedRuntimeJar(entryName, objectKey, jarBytes, sha256(jarBytes)));
                }
                zipInputStream.closeEntry();
                entry = zipInputStream.getNextEntry();
            }
        }
        if (jars.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "ZIP dependency contains no JAR files");
        }
        return jars;
    }

    private EnvironmentDependencyView updateEnabled(Long id, boolean enabled) {
        requireManager();
        EnvironmentDependencyEntity entity = requireEntity(id);
        entity.setEnabled(enabled ? Integer.valueOf(1) : Integer.valueOf(0));
        dependencyMapper.updateById(entity);
        invalidateReferencingEnvironments(entity.getId());
        return get(id);
    }

    private void invalidateReferencingEnvironments(Long dependencyId) {
        Set<Long> environmentIds = bumpReferencingEnvironmentVersions(dependencyId);
        if (environmentIds.isEmpty()) {
            return;
        }
        ScriptEnvironmentRuntimeService runtimeService = runtimeServiceProvider.getIfAvailable();
        for (Long environmentId : environmentIds) {
            if (runtimeService != null) {
                runtimeService.clearEnvironment(environmentId);
            }
            JavaDataDevelopmentExecutor.clearCompiledCache(environmentId, null);
        }
    }

    private Set<Long> bumpReferencingEnvironmentVersions(Long dependencyId) {
        Set<Long> environmentIds = new LinkedHashSet<Long>();
        if (dependencyId == null) {
            return environmentIds;
        }
        List<ScriptEnvironmentDependencyRelEntity> relations = relationMapper.selectList(new LambdaQueryWrapper<ScriptEnvironmentDependencyRelEntity>()
                .eq(ScriptEnvironmentDependencyRelEntity::getTenantId, securityService.currentTenantId())
                .eq(ScriptEnvironmentDependencyRelEntity::getDependencyId, dependencyId)
                .orderByAsc(ScriptEnvironmentDependencyRelEntity::getEnvironmentId)
                .orderByAsc(ScriptEnvironmentDependencyRelEntity::getId));
        for (ScriptEnvironmentDependencyRelEntity relation : relations) {
            if (relation.getEnvironmentId() == null || environmentIds.contains(relation.getEnvironmentId())) {
                continue;
            }
            ScriptEnvironmentEntity environment = environmentMapper.selectById(relation.getEnvironmentId());
            if (environment == null || !securityService.currentTenantId().equals(environment.getTenantId())) {
                continue;
            }
            environment.setEnvironmentVersion(Long.valueOf(environment.getEnvironmentVersion() == null
                    ? 1L
                    : environment.getEnvironmentVersion().longValue() + 1L));
            environmentMapper.updateById(environment);
            environmentIds.add(environment.getId());
        }
        return environmentIds;
    }

    private EnvironmentDependencyEntity requireEntity(Long id) {
        if (id == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Environment dependency id is required");
        }
        EnvironmentDependencyEntity entity = dependencyMapper.selectById(id);
        if (entity == null || !securityService.currentTenantId().equals(entity.getTenantId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Environment dependency not found: " + id);
        }
        return entity;
    }

    private EnvironmentDependencyFileEntity requireVisibleFile(Long dependencyId, Long fileId) {
        if (fileId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Dependency file id is required");
        }
        EnvironmentDependencyFileEntity file = dependencyFileMapper.selectById(fileId);
        if (file == null
                || !securityService.currentTenantId().equals(file.getTenantId())
                || !dependencyId.equals(file.getDependencyId())
                || file.getVisible() == null
                || file.getVisible().intValue() != 1) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Environment dependency file not found: " + fileId);
        }
        return file;
    }

    private void validateRequest(EnvironmentDependencySaveRequest request) {
        if (request == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Environment dependency request is required");
        }
        if (!hasText(request.getName())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Dependency name is required");
        }
        if (!hasText(request.getArtifactUrl())) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Artifact URL is required");
        }
        normalizeScriptType(request.getScriptType());
        normalizeArtifactType(request.getArtifactType());
    }

    private void ensureUniqueNameVersion(String name, String version, Long selfId) {
        EnvironmentDependencyEntity duplicate = dependencyMapper.selectOne(new LambdaQueryWrapper<EnvironmentDependencyEntity>()
                .eq(EnvironmentDependencyEntity::getTenantId, securityService.currentTenantId())
                .eq(EnvironmentDependencyEntity::getName, name)
                .eq(version != null, EnvironmentDependencyEntity::getVersion, version)
                .isNull(version == null, EnvironmentDependencyEntity::getVersion)
                .last("limit 1"));
        if (duplicate == null || (selfId != null && selfId.equals(duplicate.getId()))) {
            return;
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "Dependency name and version already exist");
    }

    private String normalizeScriptType(String scriptType) {
        String normalized = normalizeNullableText(scriptType);
        if (normalized == null) {
            return DEFAULT_SCRIPT_TYPE;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        try {
            ScriptType.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Script type is invalid: " + scriptType);
        }
        return normalized;
    }

    private String normalizeArtifactType(String artifactType) {
        String normalized = normalizeText(artifactType);
        if (normalized == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Artifact type is required");
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!"JAR".equals(normalized) && !"ZIP".equals(normalized)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Artifact type must be JAR or ZIP");
        }
        return normalized;
    }

    private String inferArtifactType(String fileName) {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".jar")) {
            return "JAR";
        }
        if (lowerName.endsWith(".zip")) {
            return "ZIP";
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "Dependency file must be a JAR or ZIP");
    }

    private List<MultipartFile> normalizeUploadFiles(List<MultipartFile> files) {
        List<MultipartFile> result = new ArrayList<MultipartFile>();
        if (files == null) {
            return result;
        }
        for (MultipartFile file : files) {
            if (file != null && !file.isEmpty()) {
                result.add(file);
            }
        }
        return result;
    }

    private EnvironmentDependencyFileEntity findVisibleFile(Long dependencyId, String originalFileName) {
        List<EnvironmentDependencyFileEntity> files = dependencyFileMapper.selectList(new LambdaQueryWrapper<EnvironmentDependencyFileEntity>()
                .eq(EnvironmentDependencyFileEntity::getTenantId, securityService.currentTenantId())
                .eq(EnvironmentDependencyFileEntity::getDependencyId, dependencyId)
                .eq(EnvironmentDependencyFileEntity::getVisible, Integer.valueOf(1))
                .eq(EnvironmentDependencyFileEntity::getOriginalFileName, originalFileName)
                .orderByAsc(EnvironmentDependencyFileEntity::getId)
                .last("limit 1"));
        return files.isEmpty() ? null : files.get(0);
    }

    private EnvironmentDependencyFileEntity findExtractedFile(Long sourceFileId, String originalFileName) {
        List<EnvironmentDependencyFileEntity> files = dependencyFileMapper.selectList(new LambdaQueryWrapper<EnvironmentDependencyFileEntity>()
                .eq(EnvironmentDependencyFileEntity::getTenantId, securityService.currentTenantId())
                .eq(EnvironmentDependencyFileEntity::getVisible, Integer.valueOf(0))
                .eq(EnvironmentDependencyFileEntity::getSourceFileId, sourceFileId)
                .eq(EnvironmentDependencyFileEntity::getOriginalFileName, originalFileName)
                .orderByAsc(EnvironmentDependencyFileEntity::getId)
                .last("limit 1"));
        return files.isEmpty() ? null : files.get(0);
    }

    private List<EnvironmentDependencyFileEntity> listVisibleFiles(Long dependencyId) {
        if (dependencyId == null) {
            return new ArrayList<EnvironmentDependencyFileEntity>();
        }
        return dependencyFileMapper.selectList(new LambdaQueryWrapper<EnvironmentDependencyFileEntity>()
                .eq(EnvironmentDependencyFileEntity::getTenantId, securityService.currentTenantId())
                .eq(EnvironmentDependencyFileEntity::getDependencyId, dependencyId)
                .eq(EnvironmentDependencyFileEntity::getVisible, Integer.valueOf(1))
                .eq(EnvironmentDependencyFileEntity::getEnabled, Integer.valueOf(1))
                .orderByAsc(EnvironmentDependencyFileEntity::getOriginalFileName)
                .orderByAsc(EnvironmentDependencyFileEntity::getId));
    }

    private List<EnvironmentDependencyFileEntity> listAllFiles(Long dependencyId) {
        if (dependencyId == null) {
            return new ArrayList<EnvironmentDependencyFileEntity>();
        }
        return dependencyFileMapper.selectList(new LambdaQueryWrapper<EnvironmentDependencyFileEntity>()
                .eq(EnvironmentDependencyFileEntity::getTenantId, securityService.currentTenantId())
                .eq(EnvironmentDependencyFileEntity::getDependencyId, dependencyId)
                .orderByAsc(EnvironmentDependencyFileEntity::getVisible)
                .orderByAsc(EnvironmentDependencyFileEntity::getId));
    }

    private List<EnvironmentDependencyFileEntity> listExtractedFiles(Long sourceFileId) {
        if (sourceFileId == null) {
            return new ArrayList<EnvironmentDependencyFileEntity>();
        }
        return dependencyFileMapper.selectList(new LambdaQueryWrapper<EnvironmentDependencyFileEntity>()
                .eq(EnvironmentDependencyFileEntity::getTenantId, securityService.currentTenantId())
                .eq(EnvironmentDependencyFileEntity::getSourceFileId, sourceFileId)
                .eq(EnvironmentDependencyFileEntity::getVisible, Integer.valueOf(0))
                .orderByAsc(EnvironmentDependencyFileEntity::getId));
    }

    private void deleteExtractedArtifacts(Long sourceFileId) {
        for (EnvironmentDependencyFileEntity file : listExtractedFiles(sourceFileId)) {
            deleteObjectQuietly(file);
            dependencyFileMapper.deleteById(file.getId());
        }
    }

    private void deleteStaleExtractedArtifacts(Long sourceFileId, Set<String> currentNames) {
        for (EnvironmentDependencyFileEntity file : listExtractedFiles(sourceFileId)) {
            if (currentNames.contains(file.getOriginalFileName())) {
                continue;
            }
            deleteObjectQuietly(file);
            dependencyFileMapper.deleteById(file.getId());
        }
    }

    private void refreshLegacyArtifactColumns(Long dependencyId) {
        EnvironmentDependencyEntity dependency = dependencyMapper.selectById(dependencyId);
        if (dependency == null) {
            return;
        }
        List<EnvironmentDependencyFileEntity> visibleFiles = listVisibleFiles(dependencyId);
        if (visibleFiles.isEmpty()) {
            dependency.setArtifactUrl(null);
            dependency.setArtifactType(null);
            dependency.setChecksum(null);
        } else {
            EnvironmentDependencyFileEntity first = visibleFiles.get(0);
            dependency.setArtifactUrl(first.getObjectUrl());
            dependency.setArtifactType(first.getArtifactType());
            dependency.setChecksum(first.getChecksum());
        }
        dependencyMapper.updateById(dependency);
    }

    private List<EnvironmentDependencyFileView> toFileViews(List<EnvironmentDependencyFileEntity> files) {
        List<EnvironmentDependencyFileView> result = new ArrayList<EnvironmentDependencyFileView>();
        for (EnvironmentDependencyFileEntity file : files) {
            result.add(toFileView(file));
        }
        return result;
    }

    private EnvironmentDependencyFileView toFileView(EnvironmentDependencyFileEntity file) {
        EnvironmentDependencyFileView view = new EnvironmentDependencyFileView();
        view.setId(file.getId());
        view.setTenantId(file.getTenantId());
        view.setDeleted(file.getDeleted() != null && file.getDeleted().intValue() == 1);
        view.setCreatedAt(file.getCreatedAt());
        view.setUpdatedAt(file.getUpdatedAt());
        view.setDependencyId(file.getDependencyId());
        view.setOriginalFileName(file.getOriginalFileName());
        view.setArtifactType(file.getArtifactType());
        view.setChecksum(file.getChecksum());
        view.setSizeBytes(file.getSizeBytes());
        view.setVisible(file.getVisible() != null && file.getVisible().intValue() == 1);
        view.setRuntimeArtifact(file.getRuntimeArtifact() != null && file.getRuntimeArtifact().intValue() == 1);
        view.setSourceFileId(file.getSourceFileId());
        view.setEnabled(file.getEnabled() != null && file.getEnabled().intValue() == 1);
        view.setUploadedAt(file.getCreatedAt() == null ? LocalDateTime.now() : file.getCreatedAt());
        return view;
    }

    private EnvironmentDependencyFileView toLegacyFileView(EnvironmentDependencyEntity entity) {
        EnvironmentDependencyFileView view = new EnvironmentDependencyFileView();
        view.setDependencyId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setOriginalFileName(legacyFileName(entity.getArtifactUrl()));
        view.setArtifactType(entity.getArtifactType());
        view.setChecksum(entity.getChecksum());
        view.setVisible(Boolean.TRUE);
        view.setRuntimeArtifact(Boolean.TRUE);
        view.setEnabled(Boolean.TRUE);
        view.setUploadedAt(entity.getUpdatedAt() == null ? entity.getCreatedAt() : entity.getUpdatedAt());
        return view;
    }

    private String legacyFileName(String artifactUrl) {
        String value = normalizeNullableText(artifactUrl);
        if (value == null) {
            return "legacy-artifact";
        }
        int queryIndex = value.indexOf('?');
        if (queryIndex >= 0) {
            value = value.substring(0, queryIndex);
        }
        value = value.replace('\\', '/');
        int index = value.lastIndexOf('/');
        String name = index >= 0 ? value.substring(index + 1) : value;
        return hasText(name) ? name : "legacy-artifact";
    }

    private String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte item : digest.digest()) {
            builder.append(String.format(Locale.ENGLISH, "%02x", item));
        }
        return builder.toString();
    }

    private String buildUploadedObjectKey(String scriptType, Long dependencyId, String fileName) {
        return MANAGED_OBJECT_PREFIX
                + scriptType.toLowerCase(Locale.ROOT)
                + "/"
                + dependencyId
                + "/"
                + fileName;
    }

    private String buildExtractedObjectKey(String scriptType, Long dependencyId, String zipFileName, String entryName) {
        return MANAGED_OBJECT_PREFIX
                + scriptType.toLowerCase(Locale.ROOT)
                + "/"
                + dependencyId
                + "/"
                + zipFileName
                + ".extracted/"
                + entryName;
    }

    private String normalizeOriginalFilename(String originalFilename) {
        String value = normalizeNullableText(originalFilename);
        if (value == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Dependency file name is required");
        }
        String pathNormalized = value.replace('\\', '/');
        int index = pathNormalized.lastIndexOf('/');
        if (index >= 0) {
            value = pathNormalized.substring(index + 1);
        }
        if (!isSafeFileName(value)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Dependency file name is invalid: " + value);
        }
        return value;
    }

    private String normalizeZipJarEntryName(String entryName) {
        String normalized = normalizeNullableText(entryName);
        if (normalized == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "ZIP dependency contains invalid JAR entry");
        }
        normalized = normalized.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.endsWith("/") || containsControlCharacter(normalized)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "ZIP dependency contains invalid JAR entry: " + entryName);
        }
        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if (!isSafeFileName(segment)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "ZIP dependency contains invalid JAR entry: " + entryName);
            }
        }
        return normalized;
    }

    private boolean isSafeFileName(String value) {
        return hasText(value)
                && !".".equals(value)
                && !"..".equals(value)
                && value.indexOf('/') < 0
                && value.indexOf('\\') < 0
                && !containsControlCharacter(value);
    }

    private boolean containsControlCharacter(String value) {
        if (value == null) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch < 32 || ch == 127) {
                return true;
            }
        }
        return false;
    }

    private String contentType(String artifactType) {
        return "ZIP".equalsIgnoreCase(artifactType) ? "application/zip" : "application/java-archive";
    }

    private void deleteObjectQuietly(EnvironmentDependencyFileEntity file) {
        OssArtifact artifact = resolveFileArtifact(file);
        if (artifact == null) {
            return;
        }
        try {
            cloudObjectStorageService.delete(artifact.bucket, artifact.objectKey);
        } catch (RuntimeException ex) {
            log.warn("Failed to delete dependency object {}", file.getObjectUrl(), ex);
        }
    }

    private void deleteManagedArtifactQuietly(String artifactUrl) {
        OssArtifact artifact = parseManagedOssArtifact(artifactUrl);
        if (artifact == null) {
            return;
        }
        try {
            cloudObjectStorageService.delete(artifact.bucket, artifact.objectKey);
        } catch (RuntimeException ex) {
            log.warn("Failed to delete old dependency artifact {}", artifactUrl, ex);
        }
    }

    private OssArtifact resolveFileArtifact(EnvironmentDependencyFileEntity file) {
        if (file == null) {
            return null;
        }
        OssArtifact byUrl = parseManagedOssArtifact(file.getObjectUrl());
        if (byUrl != null) {
            return byUrl;
        }
        String objectKey = normalizeNullableText(file.getObjectKey());
        if (objectKey == null) {
            return null;
        }
        return new OssArtifact(cloudObjectStorageService.resolveBucket(), objectKey);
    }

    private OssArtifact parseManagedOssArtifact(String artifactUrl) {
        String normalized = normalizeNullableText(artifactUrl);
        if (normalized == null || !normalized.startsWith(OSS_PREFIX)) {
            return null;
        }
        String value = normalized.substring(OSS_PREFIX.length());
        int splitIndex = value.indexOf('/');
        if (splitIndex <= 0 || splitIndex >= value.length() - 1) {
            return null;
        }
        String objectKey = value.substring(splitIndex + 1);
        if (!objectKey.startsWith(MANAGED_OBJECT_PREFIX)) {
            return null;
        }
        return new OssArtifact(value.substring(0, splitIndex), objectKey);
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.getClass().getSimpleName() : cursor.getMessage();
    }

    private void requireManager() {
        if (!securityService.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN,
                StudioConstants.ROLE_TENANT_ADMIN,
                StudioConstants.ROLE_ADMIN,
                StudioConstants.ROLE_PROJECT_ADMIN)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Operation is not allowed in the current context");
        }
    }

    private int normalizePageNo(Integer pageNo) {
        return pageNo == null || pageNo.intValue() < 1 ? 1 : pageNo.intValue();
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize.intValue() < 1) {
            return 20;
        }
        return Math.min(pageSize.intValue(), 200);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String normalizeText(String value) {
        return value == null ? null : value.trim();
    }

    private String normalizeNullableText(String value) {
        String normalized = normalizeText(value);
        return hasText(normalized) ? normalized : null;
    }

    public static final class DependencyFileDownload {
        private final String fileName;
        private final String contentType;
        private final byte[] bytes;

        private DependencyFileDownload(String fileName, String contentType, byte[] bytes) {
            this.fileName = fileName;
            this.contentType = contentType;
            this.bytes = bytes;
        }

        public String getFileName() {
            return fileName;
        }

        public String getContentType() {
            return contentType;
        }

        public byte[] getBytes() {
            return bytes;
        }
    }

    private static final class PreparedUpload {
        private final String originalFileName;
        private final String artifactType;
        private final byte[] bytes;
        private final String checksum;
        private final List<PreparedRuntimeJar> runtimeJars;

        private PreparedUpload(String originalFileName,
                               String artifactType,
                               byte[] bytes,
                               String checksum,
                               List<PreparedRuntimeJar> runtimeJars) {
            this.originalFileName = originalFileName;
            this.artifactType = artifactType;
            this.bytes = bytes;
            this.checksum = checksum;
            this.runtimeJars = runtimeJars;
        }
    }

    private static final class PreparedRuntimeJar {
        private final String originalFileName;
        private final String objectKey;
        private final byte[] bytes;
        private final String checksum;

        private PreparedRuntimeJar(String originalFileName, String objectKey, byte[] bytes, String checksum) {
            this.originalFileName = originalFileName;
            this.objectKey = objectKey;
            this.bytes = bytes;
            this.checksum = checksum;
        }
    }

    private static final class OssArtifact {
        private final String bucket;
        private final String objectKey;

        private OssArtifact(String bucket, String objectKey) {
            this.bucket = bucket;
            this.objectKey = objectKey;
        }
    }
}
