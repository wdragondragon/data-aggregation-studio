package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.FileTransferScheduleDefinition;
import com.jdragon.studio.dto.model.FileTransferTaskDefinitionView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.FileTransferTaskSaveRequest;
import com.jdragon.studio.dto.enums.UnstructuredAclPermission;
import com.jdragon.studio.infra.entity.FileTransferTaskDefinitionEntity;
import com.jdragon.studio.infra.mapper.FileTransferTaskDefinitionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class FileTransferTaskService {

    private static final Set<String> FILE_TYPES = Set.of("local", "ftp", "sftp", "minio", "oss");
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final FileTransferTaskDefinitionMapper mapper;
    private final DataSourceService dataSourceService;
    private final RuntimeClusterSelectionService runtimeClusterSelectionService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final StudioSecurityService securityService;
    private final UnstructuredManagementService unstructuredManagementService;
    private final ObjectMapper objectMapper;

    public FileTransferTaskService(FileTransferTaskDefinitionMapper mapper,
                                   DataSourceService dataSourceService,
                                   RuntimeClusterSelectionService runtimeClusterSelectionService,
                                   ProjectResourceAccessService projectResourceAccessService,
                                   StudioSecurityService securityService,
                                   UnstructuredManagementService unstructuredManagementService,
                                   ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.dataSourceService = dataSourceService;
        this.runtimeClusterSelectionService = runtimeClusterSelectionService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.securityService = securityService;
        this.unstructuredManagementService = unstructuredManagementService;
        this.objectMapper = objectMapper;
    }

    public PageView<FileTransferTaskDefinitionView> listPage(Integer pageNo, Integer pageSize,
                                                              String keyword, String status) {
        int safePageNo = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int safePageSize = pageSize == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
        LambdaQueryWrapper<FileTransferTaskDefinitionEntity> query = accessibleQuery();
        if (StringUtils.hasText(keyword)) {
            String pattern = "%" + keyword.trim() + "%";
            query.and(wrapper -> wrapper.like(FileTransferTaskDefinitionEntity::getName, pattern)
                    .or().like(FileTransferTaskDefinitionEntity::getCode, pattern));
        }
        if (StringUtils.hasText(status)) {
            query.eq(FileTransferTaskDefinitionEntity::getStatus, status.trim().toUpperCase());
        }
        query.orderByDesc(FileTransferTaskDefinitionEntity::getUpdatedAt);
        Page<FileTransferTaskDefinitionEntity> page = mapper.selectPage(
                new Page<FileTransferTaskDefinitionEntity>(safePageNo, safePageSize), query);
        List<FileTransferTaskDefinitionView> items = new ArrayList<FileTransferTaskDefinitionView>();
        for (FileTransferTaskDefinitionEntity entity : page.getRecords()) {
            items.add(toView(entity));
        }
        return PageView.of(safePageNo, safePageSize, page.getTotal(), items);
    }

    public FileTransferTaskDefinitionView get(Long id) {
        return findAccessible(id).map(this::toView).orElse(null);
    }

    public FileTransferTaskDefinitionEntity requireOnlineEntity(Long id) {
        FileTransferTaskDefinitionEntity entity = findAccessible(id)
                .orElseThrow(() -> new StudioException(StudioErrorCode.NOT_FOUND,
                        "File transfer task not found: " + id));
        if (!"ONLINE".equalsIgnoreCase(entity.getStatus()) || entity.getPublishedVersion() == null
                || entity.getPublishedSnapshotJson() == null || entity.getPublishedSnapshotJson().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "File transfer task must be published before running");
        }
        return entity;
    }

    public FileTransferTaskDefinitionEntity requireOnlineForExecution(Long id) {
        FileTransferTaskDefinitionEntity entity = requireOnlineEntity(id);
        Long runtimeClusterId = requireSingleCluster(entity.getRuntimeClusterId(),
                entity.getSourceRuntimeClusterId(), entity.getTargetRuntimeClusterId());
        runtimeClusterSelectionService.assertExistingResourceRunnable(entity.getProjectId(),
                runtimeClusterId, List.of(entity.getSourceDatasourceId()));
        runtimeClusterSelectionService.assertExistingResourceRunnable(entity.getProjectId(),
                runtimeClusterId, List.of(entity.getTargetDatasourceId()));
        assertTransferPermissions(runtimeClusterId, entity.getSourceDatasourceId(), entity.getSelectionJson(),
                entity.getTargetDatasourceId(), entity.getMappingJson());
        return entity;
    }

    @Transactional
    public FileTransferTaskDefinitionView save(FileTransferTaskSaveRequest request) {
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        FileTransferTaskDefinitionEntity entity = request.getId() == null
                ? new FileTransferTaskDefinitionEntity() : requireWritable(request.getId());
        validateRequest(projectId, request);
        ensureUnique(projectId, request.getName(), request.getCode(), entity.getId());
        DataSourceDefinition source = requireFileDatasource(projectId, request.getSourceDatasourceId());
        DataSourceDefinition target = requireFileDatasource(projectId, request.getTargetDatasourceId());
        Long runtimeClusterId = requireSingleCluster(request.getRuntimeClusterId(),
                request.getSourceRuntimeClusterId(), request.getTargetRuntimeClusterId());
        runtimeClusterSelectionService.validateDatasourceSelection(projectId,
                runtimeClusterId, List.of(source.getId()));
        runtimeClusterSelectionService.validateDatasourceSelection(projectId,
                runtimeClusterId, List.of(target.getId()));
        assertTransferPermissions(runtimeClusterId, source.getId(), request.getSelection(),
                target.getId(), request.getMapping());

        boolean created = entity.getId() == null;
        entity.setProjectId(projectId);
        entity.setTenantId(securityService.currentTenantId());
        if (created) {
            entity.setCreatedBy(securityService.currentUserId());
            entity.setStatus("DRAFT");
            entity.setVersion(1);
        } else {
            entity.setVersion(entity.getVersion() == null ? 1 : entity.getVersion() + 1);
            if ("ONLINE".equalsIgnoreCase(entity.getStatus())) {
                entity.setStatus("DRAFT");
            }
        }
        entity.setName(request.getName().trim());
        entity.setCode(request.getCode().trim());
        entity.setRuntimeClusterId(runtimeClusterId);
        entity.setSourceRuntimeClusterId(runtimeClusterId);
        entity.setSourceDatasourceId(source.getId());
        entity.setSourceDatasourceNameSnapshot(source.getName());
        entity.setSourceDatasourceTypeSnapshot(source.getTypeCode());
        entity.setTargetRuntimeClusterId(runtimeClusterId);
        entity.setTargetDatasourceId(target.getId());
        entity.setTargetDatasourceNameSnapshot(target.getName());
        entity.setTargetDatasourceTypeSnapshot(target.getTypeCode());
        entity.setSelectionJson(copy(request.getSelection()));
        entity.setMappingJson(copy(request.getMapping()));
        entity.setPolicyJson(withPolicyDefaults(request.getPolicy()));
        entity.setRuntimeJson(withRuntimeDefaults(request.getRuntime()));
        applySchedule(entity, request.getSchedule());
        if (created) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        return toView(entity);
    }

    public Map<String, Object> validate(Long id) {
        FileTransferTaskDefinitionEntity entity = findAccessible(id)
                .orElseThrow(() -> new StudioException(StudioErrorCode.NOT_FOUND,
                        "File transfer task not found: " + id));
        FileTransferTaskSaveRequest request = toSaveRequest(entity);
        validateRequest(entity.getProjectId(), request);
        requireFileDatasource(entity.getProjectId(), entity.getSourceDatasourceId());
        requireFileDatasource(entity.getProjectId(), entity.getTargetDatasourceId());
        Long runtimeClusterId = requireSingleCluster(entity.getRuntimeClusterId(),
                entity.getSourceRuntimeClusterId(), entity.getTargetRuntimeClusterId());
        runtimeClusterSelectionService.validateDatasourceSelection(entity.getProjectId(),
                runtimeClusterId, List.of(entity.getSourceDatasourceId()));
        runtimeClusterSelectionService.validateDatasourceSelection(entity.getProjectId(),
                runtimeClusterId, List.of(entity.getTargetDatasourceId()));
        assertTransferPermissions(runtimeClusterId, entity.getSourceDatasourceId(), entity.getSelectionJson(),
                entity.getTargetDatasourceId(), entity.getMappingJson());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("valid", Boolean.TRUE);
        result.put("version", entity.getVersion());
        result.put("sourceType", entity.getSourceDatasourceTypeSnapshot());
        result.put("targetType", entity.getTargetDatasourceTypeSnapshot());
        result.put("runtimeClusterId", runtimeClusterId);
        result.put("crossCluster", Boolean.FALSE);
        return result;
    }

    @Transactional
    public FileTransferTaskDefinitionView publish(Long id) {
        FileTransferTaskDefinitionEntity entity = requireWritable(id);
        validate(id);
        Long runtimeClusterId = requireSingleCluster(entity.getRuntimeClusterId(),
                entity.getSourceRuntimeClusterId(), entity.getTargetRuntimeClusterId());
        runtimeClusterSelectionService.assertExistingResourceRunnable(entity.getProjectId(),
                runtimeClusterId, List.of(entity.getSourceDatasourceId()));
        runtimeClusterSelectionService.assertExistingResourceRunnable(entity.getProjectId(),
                runtimeClusterId, List.of(entity.getTargetDatasourceId()));
        entity.setPublishedVersion(entity.getVersion());
        entity.setPublishedSnapshotJson(snapshot(entity));
        entity.setStatus("ONLINE");
        mapper.updateById(entity);
        return toView(entity);
    }

    @Transactional
    public FileTransferTaskDefinitionView offline(Long id) {
        FileTransferTaskDefinitionEntity entity = requireWritable(id);
        entity.setStatus("OFFLINE");
        mapper.updateById(entity);
        return toView(entity);
    }

    @Transactional
    public void delete(Long id) {
        mapper.deleteById(requireWritable(id));
    }

    public List<FileTransferTaskDefinitionEntity> findEnabledSchedules() {
        return mapper.selectList(new LambdaQueryWrapper<FileTransferTaskDefinitionEntity>()
                .eq(FileTransferTaskDefinitionEntity::getStatus, "ONLINE")
                .eq(FileTransferTaskDefinitionEntity::getScheduleEnabled, 1)
                .isNotNull(FileTransferTaskDefinitionEntity::getCronExpression)
                .orderByAsc(FileTransferTaskDefinitionEntity::getId));
    }

    public void markScheduleTriggered(Long taskId, LocalDateTime triggeredAt) {
        FileTransferTaskDefinitionEntity entity = mapper.selectById(taskId);
        if (entity != null) {
            entity.setLastTriggeredAt(triggeredAt);
            mapper.updateById(entity);
        }
    }

    public Map<String, Object> publishedSnapshot(Long id) {
        FileTransferTaskDefinitionEntity entity = requireOnlineForExecution(id);
        return new LinkedHashMap<String, Object>(entity.getPublishedSnapshotJson());
    }

    private void validateRequest(Long projectId, FileTransferTaskSaveRequest request) {
        if (request == null) {
            throw bad("File transfer task payload is required");
        }
        if (!StringUtils.hasText(request.getName()) || !StringUtils.hasText(request.getCode())) {
            throw bad("Task name and code are required");
        }
        Map<String, Object> selection = request.getSelection();
        Map<String, Object> mapping = request.getMapping();
        if (selection == null || !StringUtils.hasText(asString(selection.get("rootPath")))) {
            throw bad("selection.rootPath is required");
        }
        if (mapping == null || !StringUtils.hasText(asString(mapping.get("targetRootPath")))) {
            throw bad("mapping.targetRootPath is required");
        }
        FileTransferScheduleDefinition schedule = request.getSchedule();
        if (schedule != null && Boolean.TRUE.equals(schedule.getEnabled())) {
            if (!StringUtils.hasText(schedule.getCronExpression())) {
                throw bad("Cron expression is required when schedule is enabled");
            }
            try {
                ZoneId.of(StringUtils.hasText(schedule.getTimezone()) ? schedule.getTimezone() : "Asia/Shanghai");
            } catch (RuntimeException ex) {
                throw bad("Schedule timezone is invalid");
            }
        }
        if (projectId == null) {
            throw bad("Project context is required");
        }
        requireSingleCluster(request.getRuntimeClusterId(), request.getSourceRuntimeClusterId(),
                request.getTargetRuntimeClusterId());
    }

    private DataSourceDefinition requireFileDatasource(Long projectId, Long datasourceId) {
        DataSourceDefinition datasource = dataSourceService.getInternalForProject(projectId, datasourceId);
        if (datasource == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + datasourceId);
        }
        String type = datasource.getTypeCode() == null ? "" : datasource.getTypeCode().trim().toLowerCase();
        if (!FILE_TYPES.contains(type)) {
            throw bad("Datasource type does not support binary file transfer: " + datasource.getTypeCode());
        }
        return datasource;
    }

    private void assertTransferPermissions(Long runtimeClusterId,
                                           Long sourceDatasourceId,
                                           Map<String, Object> selection,
                                           Long targetDatasourceId,
                                           Map<String, Object> mapping) {
        unstructuredManagementService.assertPermission(runtimeClusterId, sourceDatasourceId,
                asString(selection == null ? null : selection.get("rootPath")),
                UnstructuredAclPermission.DOWNLOAD);
        unstructuredManagementService.assertPermission(runtimeClusterId, targetDatasourceId,
                asString(mapping == null ? null : mapping.get("targetRootPath")),
                UnstructuredAclPermission.EDIT);
    }

    private void ensureUnique(Long projectId, String name, String code, Long selfId) {
        List<FileTransferTaskDefinitionEntity> values = mapper.selectList(
                new LambdaQueryWrapper<FileTransferTaskDefinitionEntity>()
                        .eq(FileTransferTaskDefinitionEntity::getTenantId, securityService.currentTenantId())
                        .eq(FileTransferTaskDefinitionEntity::getProjectId, projectId)
                        .and(wrapper -> wrapper.eq(FileTransferTaskDefinitionEntity::getName, name.trim())
                                .or().eq(FileTransferTaskDefinitionEntity::getCode, code.trim())));
        for (FileTransferTaskDefinitionEntity value : values) {
            if (selfId != null && selfId.equals(value.getId())) {
                continue;
            }
            if (name.trim().equals(value.getName())) {
                throw bad("File transfer task name already exists in the current project");
            }
            throw bad("File transfer task code already exists in the current project");
        }
    }

    private Optional<FileTransferTaskDefinitionEntity> findAccessible(Long id) {
        FileTransferTaskDefinitionEntity entity = mapper.selectById(id);
        if (entity == null || !Objects.equals(entity.getTenantId(), securityService.currentTenantId())) {
            return Optional.empty();
        }
        projectResourceAccessService.assertReadable(StudioConstants.RESOURCE_TYPE_FILE_TRANSFER_TASK,
                entity.getProjectId(), entity.getId(), "File transfer task not found: " + id);
        return Optional.of(entity);
    }

    private FileTransferTaskDefinitionEntity requireWritable(Long id) {
        FileTransferTaskDefinitionEntity entity = mapper.selectById(id);
        if (entity == null || !Objects.equals(entity.getTenantId(), securityService.currentTenantId())) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "File transfer task not found: " + id);
        }
        projectResourceAccessService.assertWritable(entity.getProjectId());
        return entity;
    }

    private LambdaQueryWrapper<FileTransferTaskDefinitionEntity> accessibleQuery() {
        LambdaQueryWrapper<FileTransferTaskDefinitionEntity> query = new LambdaQueryWrapper<FileTransferTaskDefinitionEntity>()
                .eq(FileTransferTaskDefinitionEntity::getTenantId, securityService.currentTenantId());
        Long projectId = projectResourceAccessService.currentProjectId();
        if (projectId == null) {
            return query;
        }
        List<Long> shared = projectResourceAccessService.sharedResourceIdList(
                StudioConstants.RESOURCE_TYPE_FILE_TRANSFER_TASK);
        if (shared.isEmpty()) {
            return query.eq(FileTransferTaskDefinitionEntity::getProjectId, projectId);
        }
        return query.and(wrapper -> wrapper.eq(FileTransferTaskDefinitionEntity::getProjectId, projectId)
                .or().in(FileTransferTaskDefinitionEntity::getId, shared));
    }

    private FileTransferTaskDefinitionView toView(FileTransferTaskDefinitionEntity entity) {
        FileTransferTaskDefinitionView view = new FileTransferTaskDefinitionView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setProjectId(entity.getProjectId());
        view.setDeleted(Integer.valueOf(1).equals(entity.getDeleted()));
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setName(entity.getName());
        view.setCode(entity.getCode());
        view.setStatus(entity.getStatus());
        view.setVersion(entity.getVersion());
        view.setPublishedVersion(entity.getPublishedVersion());
        view.setRuntimeClusterId(singleClusterForView(entity));
        view.setSourceRuntimeClusterId(entity.getSourceRuntimeClusterId());
        view.setSourceRuntimeClusterName(runtimeClusterSelectionService.runtimeClusterName(
                entity.getProjectId(), entity.getSourceRuntimeClusterId()));
        view.setSourceDatasourceId(entity.getSourceDatasourceId());
        view.setSourceDatasourceName(entity.getSourceDatasourceNameSnapshot());
        view.setSourceDatasourceType(entity.getSourceDatasourceTypeSnapshot());
        view.setTargetRuntimeClusterId(entity.getTargetRuntimeClusterId());
        view.setTargetRuntimeClusterName(runtimeClusterSelectionService.runtimeClusterName(
                entity.getProjectId(), entity.getTargetRuntimeClusterId()));
        view.setTargetDatasourceId(entity.getTargetDatasourceId());
        view.setTargetDatasourceName(entity.getTargetDatasourceNameSnapshot());
        view.setTargetDatasourceType(entity.getTargetDatasourceTypeSnapshot());
        view.setSelection(copy(entity.getSelectionJson()));
        view.setMapping(copy(entity.getMappingJson()));
        view.setPolicy(copy(entity.getPolicyJson()));
        view.setRuntime(copy(entity.getRuntimeJson()));
        FileTransferScheduleDefinition schedule = new FileTransferScheduleDefinition();
        schedule.setEnabled(Integer.valueOf(1).equals(entity.getScheduleEnabled()));
        schedule.setCronExpression(entity.getCronExpression());
        schedule.setTimezone(entity.getTimezone());
        view.setSchedule(schedule);
        return view;
    }

    private FileTransferTaskSaveRequest toSaveRequest(FileTransferTaskDefinitionEntity entity) {
        FileTransferTaskSaveRequest request = new FileTransferTaskSaveRequest();
        request.setId(entity.getId());
        request.setName(entity.getName());
        request.setCode(entity.getCode());
        request.setRuntimeClusterId(singleClusterForView(entity));
        request.setSourceRuntimeClusterId(entity.getSourceRuntimeClusterId());
        request.setSourceDatasourceId(entity.getSourceDatasourceId());
        request.setTargetRuntimeClusterId(entity.getTargetRuntimeClusterId());
        request.setTargetDatasourceId(entity.getTargetDatasourceId());
        request.setSelection(copy(entity.getSelectionJson()));
        request.setMapping(copy(entity.getMappingJson()));
        request.setPolicy(copy(entity.getPolicyJson()));
        request.setRuntime(copy(entity.getRuntimeJson()));
        FileTransferScheduleDefinition schedule = new FileTransferScheduleDefinition();
        schedule.setEnabled(Integer.valueOf(1).equals(entity.getScheduleEnabled()));
        schedule.setCronExpression(entity.getCronExpression());
        schedule.setTimezone(entity.getTimezone());
        request.setSchedule(schedule);
        return request;
    }

    private void applySchedule(FileTransferTaskDefinitionEntity entity, FileTransferScheduleDefinition schedule) {
        boolean enabled = schedule != null && Boolean.TRUE.equals(schedule.getEnabled());
        entity.setScheduleEnabled(enabled ? 1 : 0);
        entity.setCronExpression(schedule == null || !StringUtils.hasText(schedule.getCronExpression())
                ? null : schedule.getCronExpression().trim());
        entity.setTimezone(schedule == null || !StringUtils.hasText(schedule.getTimezone())
                ? "Asia/Shanghai" : schedule.getTimezone().trim());
    }

    private Map<String, Object> snapshot(FileTransferTaskDefinitionEntity entity) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", 1);
        result.put("taskId", entity.getId());
        result.put("taskVersion", entity.getVersion());
        result.put("runtimeClusterId", singleClusterForView(entity));
        result.put("sourceRuntimeClusterId", entity.getSourceRuntimeClusterId());
        result.put("sourceDatasourceId", entity.getSourceDatasourceId());
        result.put("sourcePlugin", entity.getSourceDatasourceTypeSnapshot());
        result.put("targetRuntimeClusterId", entity.getTargetRuntimeClusterId());
        result.put("targetDatasourceId", entity.getTargetDatasourceId());
        result.put("targetPlugin", entity.getTargetDatasourceTypeSnapshot());
        result.put("selection", copy(entity.getSelectionJson()));
        result.put("mapping", copy(entity.getMappingJson()));
        result.put("policy", copy(entity.getPolicyJson()));
        result.put("runtime", copy(entity.getRuntimeJson()));
        result.put("timeZone", entity.getTimezone());
        return result;
    }

    private Map<String, Object> withPolicyDefaults(Map<String, Object> values) {
        Map<String, Object> result = copy(values);
        result.putIfAbsent("conflictPolicy", "FAIL");
        result.putIfAbsent("checksumAlgorithm", "SHA-256");
        result.putIfAbsent("sourceSuccessAction", "KEEP");
        return result;
    }

    private Map<String, Object> withRuntimeDefaults(Map<String, Object> values) {
        Map<String, Object> result = copy(values);
        result.putIfAbsent("concurrency", 4);
        result.putIfAbsent("maxRetries", 3);
        result.putIfAbsent("retryBackoffMillis", List.of(1000L, 5000L, 15000L));
        result.putIfAbsent("chunkSizeBytes", 8L * 1024L * 1024L);
        result.putIfAbsent("checkpointIntervalMillis", 1000L);
        result.putIfAbsent("maxBytesPerSecond", 0L);
        return result;
    }

    private Map<String, Object> copy(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        return objectMapper.convertValue(value, new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private StudioException bad(String message) {
        return new StudioException(StudioErrorCode.BAD_REQUEST, message);
    }

    private Long singleClusterForView(FileTransferTaskDefinitionEntity entity) {
        Long runtimeClusterId = entity.getRuntimeClusterId() != null ? entity.getRuntimeClusterId()
                : entity.getSourceRuntimeClusterId();
        if (runtimeClusterId == null || (entity.getSourceRuntimeClusterId() != null
                && !Objects.equals(runtimeClusterId, entity.getSourceRuntimeClusterId()))
                || (entity.getTargetRuntimeClusterId() != null
                && !Objects.equals(runtimeClusterId, entity.getTargetRuntimeClusterId()))) {
            return null;
        }
        return runtimeClusterId;
    }

    private Long requireSingleCluster(Long runtimeClusterId, Long sourceRuntimeClusterId,
                                      Long targetRuntimeClusterId) {
        Long resolved = runtimeClusterId != null ? runtimeClusterId
                : sourceRuntimeClusterId != null ? sourceRuntimeClusterId : targetRuntimeClusterId;
        if (resolved == null) {
            throw bad("Runtime cluster is required");
        }
        if ((sourceRuntimeClusterId != null && !Objects.equals(resolved, sourceRuntimeClusterId))
                || (targetRuntimeClusterId != null && !Objects.equals(resolved, targetRuntimeClusterId))) {
            throw new StudioException(StudioErrorCode.FILE_TRANSFER_CROSS_CLUSTER_DISABLED,
                    "File transfer only supports source and target datasources in the same runtime cluster");
        }
        return resolved;
    }
}
