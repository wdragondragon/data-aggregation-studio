package com.jdragon.studio.worker.filetransfer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.commons.util.Configuration;
import com.jdragon.aggregation.datasource.file.transfer.TransferFileSystem;
import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeSession;
import com.jdragon.aggregation.transfer.PreparedTransfer;
import com.jdragon.aggregation.transfer.DynamicTemplateResolver;
import com.jdragon.aggregation.transfer.TransferEngine;
import com.jdragon.aggregation.transfer.TransferFileSystemFactory;
import com.jdragon.aggregation.transfer.TransferPaths;
import com.jdragon.aggregation.transfer.TransferPlanner;
import com.jdragon.aggregation.transfer.model.SourceSnapshot;
import com.jdragon.aggregation.transfer.model.SourceSuccessAction;
import com.jdragon.aggregation.transfer.model.TransferItemResult;
import com.jdragon.aggregation.transfer.model.TransferItemStatus;
import com.jdragon.aggregation.transfer.model.TransferPlan;
import com.jdragon.aggregation.transfer.model.TransferPlanItem;
import com.jdragon.aggregation.transfer.model.TransferPolicy;
import com.jdragon.aggregation.transfer.model.TransferResult;
import com.jdragon.aggregation.transfer.model.TransferRunStatus;
import com.jdragon.aggregation.transfer.model.TransferSpec;
import com.jdragon.aggregation.transfer.plugin.TransferConfigurationMapper;
import com.jdragon.studio.core.spi.NodeExecutor;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.mapper.FileTransferMetricSampleMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceClusterBindingService;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
public class FileTransferNodeExecutor implements NodeExecutor {

    private final FileTransferRunMapper runMapper;
    private final FileTransferRunItemMapper itemMapper;
    private final FileTransferMetricSampleMapper metricMapper;
    private final DataSourceService dataSourceService;
    private final DatasourceClusterBindingService datasourceClusterBindingService;
    private final AggregationSourceCapabilityProvider sourceCapabilityProvider;
    private final ObjectMapper objectMapper;
    private final TransferConfigurationMapper configurationMapper = new TransferConfigurationMapper();

    public FileTransferNodeExecutor(FileTransferRunMapper runMapper,
                                    FileTransferRunItemMapper itemMapper,
                                    FileTransferMetricSampleMapper metricMapper,
                                    DataSourceService dataSourceService,
                                    DatasourceClusterBindingService datasourceClusterBindingService,
                                    AggregationSourceCapabilityProvider sourceCapabilityProvider,
                                    ObjectMapper objectMapper) {
        this.runMapper = runMapper;
        this.itemMapper = itemMapper;
        this.metricMapper = metricMapper;
        this.dataSourceService = dataSourceService;
        this.datasourceClusterBindingService = datasourceClusterBindingService;
        this.sourceCapabilityProvider = sourceCapabilityProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(WorkflowNodeDefinition definition) {
        return definition != null && definition.getNodeType() == NodeType.FILE_TRANSFER;
    }

    @Override
    public Map<String, Object> execute(WorkflowNodeDefinition definition,
                                       Map<String, Object> runtimeContext) {
        Long runId = optionalLong(definition.getConfig().get("fileTransferRunId"))
                .orElseThrow(() -> new IllegalArgumentException("fileTransferRunId is required"));
        FileTransferRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new IllegalArgumentException("File transfer run not found: " + runId);
        }
        Long actualClusterId = optionalLong(runtimeContext.get("runtimeClusterId")).orElse(null);
        Long runClusterId = requireSingleCluster(run.getRuntimeClusterId(),
                run.getSourceRuntimeClusterId(), run.getTargetRuntimeClusterId());
        if (!Objects.equals(runClusterId, actualClusterId)) {
            throw new IllegalStateException("File transfer run is executing on the wrong runtime cluster");
        }
        Long runRecordId = optionalLong(runtimeContext.get("runRecordId")).orElse(null);
        markStarted(run, runRecordId);

        Map<String, Object> runSnapshot = copy(run.getResolvedSpecJson());
        if (optionalLong(runSnapshot.get("plannedAtMillis")).isEmpty()) {
            runSnapshot.put("plannedAtMillis", Instant.now().toEpochMilli());
            run.setResolvedSpecJson(runSnapshot);
            runMapper.updateById(run);
        }
        String retryCoreItemId = text(runSnapshot.get("retryCoreItemId"), null);
        String retryMode = text(runSnapshot.get("retryMode"), "TRANSFER");
        boolean postActionOnly = "POST_ACTION_ONLY".equalsIgnoreCase(retryMode);
        List<TransferExecution> executions = buildExecutions(run, runSnapshot);
        StudioTransferEventListener listener = new StudioTransferEventListener(
                run, runMapper, itemMapper, metricMapper);
        List<TransferResult> results = new ArrayList<TransferResult>();
        try (PluginRuntimeSession pluginSession = PluginRuntimeSession.open()) {
            TransferEngine engine = new TransferEngine(pluginSession::bind);
            TransferPlanner planner = new TransferPlanner();
            List<PreparedExecution> preparedExecutions = new ArrayList<PreparedExecution>();
            long totalFiles = 0L;
            long totalBytes = 0L;
            int index = 0;
            boolean retryItemMatched = retryCoreItemId == null;
            for (TransferExecution execution : executions) {
                String coreRunId = run.getId() + "-" + (++index);
                TransferSpec spec = configurationMapper.map(Configuration.from(execution.spec));
                PreparedTransfer prepared;
                if (postActionOnly) {
                    Optional<PreparedTransfer> retryPrepared = preparePostActionRetry(
                            run, execution, spec, coreRunId, retryCoreItemId, runSnapshot);
                    if (retryPrepared.isEmpty()) {
                        continue;
                    }
                    prepared = retryPrepared.get();
                } else {
                    try (TransferFileSystem source = execution.sourceFactory.open()) {
                        prepared = planner.prepare(spec, source, coreRunId,
                                plannedAt(runSnapshot), listener);
                    }
                    if (!Boolean.TRUE.equals(runSnapshot.get("dynamicResolved"))
                            && maps(runSnapshot.get("manualItems")).isEmpty()) {
                        persistResolvedTaskSpec(run, prepared);
                        runSnapshot = copy(run.getResolvedSpecJson());
                    }
                    prepared = selectRetryItem(prepared, retryCoreItemId);
                    if (prepared.plan().items().isEmpty()) {
                        continue;
                    }
                }
                retryItemMatched = true;
                persistPlan(run, execution, prepared);
                preparedExecutions.add(new PreparedExecution(execution, prepared));
                totalFiles += prepared.plan().items().size();
                totalBytes += prepared.plan().items().stream()
                        .mapToLong(item -> item.sourceSnapshot().size()).sum();
            }
            if (!retryItemMatched) {
                throw new IllegalStateException("Retry file transfer item is not part of the current run plan");
            }
            requireManualTransferFiles(runSnapshot, retryCoreItemId, totalFiles);
            if (retryCoreItemId == null) {
                updatePlanTotals(run, totalFiles, totalBytes);
            }
            for (PreparedExecution preparedExecution : preparedExecutions) {
                if (isCanceled(run.getId())) {
                    break;
                }
                TransferResult result = postActionOnly
                        ? retryPostAction(run, preparedExecution.prepared,
                                preparedExecution.execution.sourceFactory,
                                preparedExecution.execution.targetFactory)
                        : engine.execute(preparedExecution.prepared,
                                preparedExecution.execution.sourceFactory,
                                preparedExecution.execution.targetFactory,
                                new StudioTransferCheckpointStore(run.getId(), itemMapper, objectMapper),
                                listener,
                                new DatabaseTransferControl(run.getId(), runMapper));
                results.add(result);
                persistResults(run, preparedExecution.prepared, result);
            }
            runtimeContext.put("pluginRevisions", pluginSession.revisions());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            markFailed(run, "File transfer was interrupted");
            throw new IllegalStateException("File transfer was interrupted", exception);
        } catch (RuntimeException exception) {
            markFailed(run, exception.getMessage());
            throw exception;
        } catch (Exception exception) {
            markFailed(run, exception.getMessage());
            throw new IllegalStateException("File transfer failed: " + exception.getMessage(), exception);
        } finally {
            listener.flushSample();
        }
        return finish(run, results);
    }

    private List<TransferExecution> buildExecutions(FileTransferRunEntity run,
                                                     Map<String, Object> snapshot) {
        List<Map<String, Object>> manualItems = maps(snapshot.get("manualItems"));
        if (!manualItems.isEmpty()) {
            return manualExecutions(run, snapshot, manualItems);
        }
        return List.of(taskExecution(run, snapshot));
    }

    private TransferExecution taskExecution(FileTransferRunEntity run, Map<String, Object> snapshot) {
        Long sourceClusterId = optionalLong(snapshot.get("runtimeClusterId"))
                .or(() -> optionalLong(snapshot.get("sourceRuntimeClusterId"))).orElse(null);
        Long sourceDatasourceId = optionalLong(snapshot.get("sourceDatasourceId")).orElse(null);
        Long targetClusterId = optionalLong(snapshot.get("runtimeClusterId"))
                .or(() -> optionalLong(snapshot.get("targetRuntimeClusterId"))).orElse(null);
        Long targetDatasourceId = optionalLong(snapshot.get("targetDatasourceId")).orElse(null);
        DataSourceDefinition source = requireDatasource(run, sourceClusterId, sourceDatasourceId);
        DataSourceDefinition target = requireDatasource(run, targetClusterId, targetDatasourceId);
        Map<String, Object> spec = new LinkedHashMap<String, Object>();
        spec.put("schemaVersion", 1);
        spec.put("source", endpoint(source, sourceClusterId));
        spec.put("target", endpoint(target, targetClusterId));
        spec.put("selection", map(snapshot.get("selection")));
        spec.put("mapping", map(snapshot.get("mapping")));
        spec.put("policy", map(snapshot.get("policy")));
        spec.put("runtime", map(snapshot.get("runtime")));
        spec.put("timeZone", text(snapshot.get("timeZone"), "Asia/Shanghai"));
        spec.put("parameters", map(snapshot.get("parameters")));
        return execution(run, sourceClusterId, sourceDatasourceId, targetClusterId,
                targetDatasourceId, "SOURCE_TO_TARGET", spec, source, target);
    }

    private List<TransferExecution> manualExecutions(FileTransferRunEntity run,
                                                      Map<String, Object> snapshot,
                                                      List<Map<String, Object>> items) {
        List<TransferExecution> executions = new ArrayList<TransferExecution>();
        for (Map<String, Object> item : items) {
            Long sourceClusterId = optionalLong(item.get("runtimeClusterId"))
                    .or(() -> optionalLong(item.get("sourceRuntimeClusterId"))).orElse(null);
            Long sourceDatasourceId = optionalLong(item.get("sourceDatasourceId")).orElse(null);
            Long targetClusterId = optionalLong(item.get("runtimeClusterId"))
                    .or(() -> optionalLong(item.get("targetRuntimeClusterId"))).orElse(null);
            Long targetDatasourceId = optionalLong(item.get("targetDatasourceId")).orElse(null);
            DataSourceDefinition source = requireDatasource(run, sourceClusterId, sourceDatasourceId);
            DataSourceDefinition target = requireDatasource(run, targetClusterId, targetDatasourceId);
            Map<String, Object> selection = new LinkedHashMap<String, Object>();
            selection.put("rootPath", String.valueOf(item.get("sourcePath")));
            selection.put("paths", List.of());
            selection.put("recursive", !Boolean.FALSE.equals(item.get("recursive")));
            selection.put("maxFiles", 100_000);
            Map<String, Object> mapping = new LinkedHashMap<String, Object>();
            String targetPath = String.valueOf(item.get("targetPath"));
            boolean directorySelection = Boolean.TRUE.equals(item.get("recursive"));
            // A manually selected file carries its final target file path. The core
            // planner maps a file name below targetRootPath, so split the path here
            // instead of letting it append the file name a second time.
            mapping.put("targetRootPath", directorySelection
                    ? targetPath : TransferPaths.parent(targetPath));
            // Manual file selections carry the final target file path. Directory selections
            // carry a target directory and must retain each discovered child relative path.
            mapping.put("preserveRelativePath", directorySelection);
            if (!directorySelection) {
                mapping.put("targetPathTemplate", TransferPaths.fileName(targetPath));
            }
            Map<String, Object> spec = new LinkedHashMap<String, Object>();
            spec.put("schemaVersion", 1);
            spec.put("source", endpoint(source, sourceClusterId));
            spec.put("target", endpoint(target, targetClusterId));
            spec.put("selection", selection);
            spec.put("mapping", mapping);
            spec.put("policy", map(snapshot.get("policy")));
            spec.put("runtime", map(snapshot.get("runtime")));
            spec.put("timeZone", text(snapshot.get("timeZone"), "Asia/Shanghai"));
            spec.put("parameters", map(snapshot.get("parameters")));
            executions.add(execution(run, sourceClusterId, sourceDatasourceId, targetClusterId,
                    targetDatasourceId, "SOURCE_TO_TARGET", spec, source, target));
        }
        return executions;
    }

    private TransferExecution execution(FileTransferRunEntity run,
                                         Long sourceClusterId, Long sourceDatasourceId,
                                         Long targetClusterId, Long targetDatasourceId,
                                         String direction, Map<String, Object> spec,
                                         DataSourceDefinition source, DataSourceDefinition target) {
        if (!Objects.equals(sourceClusterId, targetClusterId)) {
            throw new IllegalStateException("FILE_TRANSFER_CROSS_CLUSTER_DISABLED");
        }
        TransferFileSystemFactory sourceFactory = () -> sourceCapabilityProvider.openTransferFileSystem(source);
        TransferFileSystemFactory targetFactory = () -> sourceCapabilityProvider.openTransferFileSystem(target);
        return new TransferExecution(sourceClusterId, sourceDatasourceId,
                targetClusterId, targetDatasourceId, "SOURCE_TO_TARGET", "LOCAL_WORKER",
                spec, sourceFactory, targetFactory);
    }

    private DataSourceDefinition requireDatasource(FileTransferRunEntity run, Long clusterId,
                                                   Long datasourceId) {
        if (clusterId == null || datasourceId == null) {
            throw new IllegalArgumentException("File transfer endpoint identity is incomplete");
        }
        DataSourceDefinition datasource = dataSourceService.getInternalForProject(run.getProjectId(), datasourceId);
        if (datasource == null || !Boolean.TRUE.equals(datasource.getEnabled())
                || !Boolean.TRUE.equals(datasource.getExecutable())) {
            throw new IllegalStateException("File datasource is unavailable: " + datasourceId);
        }
        datasourceClusterBindingService.assertDatasourceApplicable(datasourceId, clusterId);
        return datasource;
    }

    private Map<String, Object> endpoint(DataSourceDefinition datasource, Long clusterId) {
        Map<String, Object> endpoint = new LinkedHashMap<String, Object>();
        endpoint.put("plugin", datasource.getTypeCode());
        endpoint.put("identity", clusterId + ":" + datasource.getId());
        endpoint.put("config", new LinkedHashMap<String, Object>());
        return endpoint;
    }

    private Long requireSingleCluster(Long runtimeClusterId, Long sourceClusterId, Long targetClusterId) {
        Long resolved = runtimeClusterId != null ? runtimeClusterId
                : sourceClusterId != null ? sourceClusterId : targetClusterId;
        if (resolved == null || (sourceClusterId != null && !Objects.equals(resolved, sourceClusterId))
                || (targetClusterId != null && !Objects.equals(resolved, targetClusterId))) {
            throw new IllegalStateException("FILE_TRANSFER_CROSS_CLUSTER_DISABLED");
        }
        return resolved;
    }

    PreparedTransfer selectRetryItem(PreparedTransfer prepared, String retryCoreItemId) {
        if (retryCoreItemId == null) {
            return prepared;
        }
        List<TransferPlanItem> selected = prepared.plan().items().stream()
                .filter(item -> retryCoreItemId.equals(item.itemId()))
                .toList();
        return new PreparedTransfer(prepared.resolvedSpec(), new TransferPlan(
                prepared.plan().runId(), prepared.plan().plannedAt(), selected));
    }

    void requireManualTransferFiles(Map<String, Object> snapshot,
                                    String retryCoreItemId,
                                    long totalFiles) {
        if (retryCoreItemId == null && totalFiles == 0L
                && !maps(snapshot == null ? null : snapshot.get("manualItems")).isEmpty()) {
            throw new IllegalStateException(
                    "FILE_TRANSFER_NO_FILES_DISCOVERED: Selected paths contain no transferable files");
        }
    }

    private Optional<PreparedTransfer> preparePostActionRetry(FileTransferRunEntity run,
                                                              TransferExecution execution,
                                                              TransferSpec spec,
                                                              String coreRunId,
                                                              String retryCoreItemId,
                                                              Map<String, Object> snapshot) {
        if (retryCoreItemId == null) {
            throw new IllegalArgumentException("Post-action retry item id is required");
        }
        FileTransferRunItemEntity item = findItem(run.getId(), retryCoreItemId);
        if (item == null) {
            throw new IllegalStateException("Post-action retry item was not found");
        }
        if (!Objects.equals(item.getSourceRuntimeClusterId(), execution.sourceClusterId)
                || !Objects.equals(item.getSourceDatasourceId(), execution.sourceDatasourceId)
                || !Objects.equals(item.getTargetRuntimeClusterId(), execution.targetClusterId)
                || !Objects.equals(item.getTargetDatasourceId(), execution.targetDatasourceId)) {
            return Optional.empty();
        }
        SourceSnapshot sourceSnapshot = objectMapper.convertValue(
                item.getSourceSnapshotJson(), SourceSnapshot.class);
        TransferSpec resolved = new DynamicTemplateResolver().resolve(
                spec, coreRunId, plannedAt(snapshot));
        String relativePath;
        try {
            relativePath = TransferPaths.relativeTo(resolved.selection().rootPath(), item.getSourcePath());
        } catch (IllegalArgumentException outsideSelectionRoot) {
            relativePath = TransferPaths.fileName(item.getSourcePath());
        }
        TransferPlanItem planItem = new TransferPlanItem(retryCoreItemId,
                resolved.source().identity(), resolved.target().identity(), item.getSourcePath(),
                item.getTargetPath(), item.getTemporaryPath(), relativePath, sourceSnapshot);
        return Optional.of(new PreparedTransfer(resolved,
                new TransferPlan(coreRunId, plannedAt(snapshot), List.of(planItem))));
    }

    TransferResult retryPostAction(FileTransferRunEntity run,
                                   PreparedTransfer prepared,
                                   TransferFileSystemFactory sourceFactory,
                                   TransferFileSystemFactory targetFactory) {
        TransferPlanItem planItem = prepared.plan().items().get(0);
        FileTransferRunItemEntity stored = findItem(run.getId(), planItem.itemId());
        Instant startedAt = Instant.now();
        int attempts = stored == null || stored.getAttempts() == null ? 1 : stored.getAttempts() + 1;
        String sourceChecksum = stored == null ? null : stored.getSourceChecksum();
        String targetChecksum = stored == null ? null : stored.getTargetChecksum();
        TransferItemResult itemResult;
        try (TransferFileSystem source = sourceFactory.open();
             TransferFileSystem target = targetFactory.open()) {
            TransferPolicy policy = prepared.resolvedSpec().policy();
            if (policy.sourceSuccessAction() == SourceSuccessAction.KEEP) {
                throw new IllegalStateException("KEEP has no source post-action to retry");
            }
            if (targetChecksum == null || !target.transferExists(planItem.targetPath())) {
                throw new IllegalStateException("Committed target cannot be verified for post-action retry");
            }
            String currentTargetChecksum = target.checksum(
                    planItem.targetPath(), policy.checksumAlgorithm());
            if (!targetChecksum.equals(currentTargetChecksum)) {
                throw new IllegalStateException("Committed target changed before post-action retry");
            }
            boolean sourceExists = source.transferExists(planItem.sourcePath());
            if (sourceExists) {
                if (!planItem.sourceSnapshot().matches(source.stat(planItem.sourcePath()))) {
                    throw new IllegalStateException("Source changed before post-action retry");
                }
                String currentSourceChecksum = source.checksum(
                        planItem.sourcePath(), policy.checksumAlgorithm());
                if (sourceChecksum == null || !sourceChecksum.equals(currentSourceChecksum)
                        || !currentTargetChecksum.equals(currentSourceChecksum)) {
                    throw new IllegalStateException("Source or target checksum changed before post-action retry");
                }
                applyPostAction(source, planItem, policy, currentSourceChecksum);
            } else if (policy.sourceSuccessAction() == SourceSuccessAction.BACKUP) {
                verifyCompletedBackup(source, planItem, policy, currentTargetChecksum);
            }
            itemResult = new TransferItemResult(planItem.itemId(), TransferItemStatus.SUCCESS,
                    planItem.sourceSnapshot().size(), planItem.sourceSnapshot().size(), 0L, attempts,
                    sourceChecksum, currentTargetChecksum, null, null, startedAt, Instant.now());
        } catch (Exception exception) {
            itemResult = new TransferItemResult(planItem.itemId(), TransferItemStatus.POST_ACTION_FAILED,
                    planItem.sourceSnapshot().size(), planItem.sourceSnapshot().size(), 0L, attempts,
                    sourceChecksum, targetChecksum, "POST_ACTION_FAILED", exception.getMessage(),
                    startedAt, Instant.now());
        }
        TransferRunStatus status = itemResult.status() == TransferItemStatus.SUCCESS
                ? TransferRunStatus.SUCCESS : TransferRunStatus.PARTIAL_SUCCESS;
        return new TransferResult(prepared.plan().runId(), status,
                List.of(itemResult), 1L,
                itemResult.status() == TransferItemStatus.SUCCESS ? 1L : 0L,
                0L, itemResult.status() == TransferItemStatus.SUCCESS ? 0L : 1L,
                planItem.sourceSnapshot().size(), itemResult.transferredBytes(), 0L,
                startedAt, Instant.now());
    }

    private void applyPostAction(TransferFileSystem source, TransferPlanItem item,
                                 TransferPolicy policy, String checksum) throws Exception {
        if (policy.sourceSuccessAction() == SourceSuccessAction.DELETE) {
            source.delete(item.sourcePath());
            return;
        }
        String backupPath = TransferPaths.join(policy.sourceBackupRootPath(), item.relativePath());
        if (source.transferExists(backupPath)) {
            if (!checksum.equals(source.checksum(backupPath, policy.checksumAlgorithm()))) {
                throw new IllegalStateException("Source backup path contains different content");
            }
            source.delete(item.sourcePath());
            return;
        }
        source.move(item.sourcePath(), backupPath, false);
    }

    private void verifyCompletedBackup(TransferFileSystem source, TransferPlanItem item,
                                       TransferPolicy policy, String targetChecksum) throws Exception {
        String backupPath = TransferPaths.join(policy.sourceBackupRootPath(), item.relativePath());
        if (!source.transferExists(backupPath)
                || !targetChecksum.equals(source.checksum(backupPath, policy.checksumAlgorithm()))) {
            throw new IllegalStateException("Source backup cannot be verified for post-action retry");
        }
    }

    private void persistResolvedTaskSpec(FileTransferRunEntity run, PreparedTransfer prepared) {
        Map<String, Object> snapshot = copy(run.getResolvedSpecJson());
        snapshot.put("selection", objectMapper.convertValue(prepared.resolvedSpec().selection(),
                new TypeReference<LinkedHashMap<String, Object>>() { }));
        snapshot.put("mapping", objectMapper.convertValue(prepared.resolvedSpec().mapping(),
                new TypeReference<LinkedHashMap<String, Object>>() { }));
        snapshot.put("policy", objectMapper.convertValue(prepared.resolvedSpec().policy(),
                new TypeReference<LinkedHashMap<String, Object>>() { }));
        snapshot.put("runtime", objectMapper.convertValue(prepared.resolvedSpec().runtime(),
                new TypeReference<LinkedHashMap<String, Object>>() { }));
        snapshot.put("timeZone", prepared.resolvedSpec().timeZone());
        snapshot.put("parameters", new LinkedHashMap<String, String>(prepared.resolvedSpec().parameters()));
        snapshot.put("plannedAtMillis", prepared.plan().plannedAt().toEpochMilli());
        snapshot.put("dynamicResolved", Boolean.TRUE);
        run.setResolvedSpecJson(snapshot);
        runMapper.updateById(run);
    }

    private Instant plannedAt(Map<String, Object> snapshot) {
        return optionalLong(snapshot == null ? null : snapshot.get("plannedAtMillis"))
                .map(Instant::ofEpochMilli)
                .orElseGet(Instant::now);
    }

    private FileTransferRunItemEntity findItem(Long runId, String coreItemId) {
        return itemMapper.selectOne(new LambdaQueryWrapper<FileTransferRunItemEntity>()
                .eq(FileTransferRunItemEntity::getRunId, runId)
                .eq(FileTransferRunItemEntity::getCoreItemId, coreItemId)
                .last("limit 1"));
    }

    private void persistPlan(FileTransferRunEntity run, TransferExecution execution,
                             PreparedTransfer prepared) {
        for (TransferPlanItem planItem : prepared.plan().items()) {
            FileTransferRunItemEntity item = itemMapper.selectOne(
                    new LambdaQueryWrapper<FileTransferRunItemEntity>()
                            .eq(FileTransferRunItemEntity::getRunId, run.getId())
                            .eq(FileTransferRunItemEntity::getCoreItemId, planItem.itemId())
                            .last("limit 1"));
            boolean created = item == null;
            if (created) {
                item = new FileTransferRunItemEntity();
                item.setTenantId(run.getTenantId());
                item.setProjectId(run.getProjectId());
                item.setRunId(run.getId());
                item.setCoreItemId(planItem.itemId());
            }
            item.setDirection(execution.direction);
            item.setChannel(execution.channel);
            item.setRuntimeClusterId(execution.targetClusterId);
            item.setSourceRuntimeClusterId(execution.sourceClusterId);
            item.setSourceDatasourceId(execution.sourceDatasourceId);
            item.setSourcePath(planItem.sourcePath());
            item.setTargetRuntimeClusterId(execution.targetClusterId);
            item.setTargetDatasourceId(execution.targetDatasourceId);
            item.setTargetPath(planItem.targetPath());
            item.setTemporaryPath(planItem.temporaryPath());
            item.setStatus("QUEUED");
            item.setFileSize(planItem.sourceSnapshot().size());
            item.setTransferredBytes(item.getTransferredBytes() == null ? 0L : item.getTransferredBytes());
            item.setResumedBytes(item.getResumedBytes() == null ? 0L : item.getResumedBytes());
            item.setCurrentBytesPerSecond(0L);
            item.setAttempts(item.getAttempts() == null ? 0 : item.getAttempts());
            item.setSourceSnapshotJson(objectMapper.convertValue(planItem.sourceSnapshot(),
                    new TypeReference<LinkedHashMap<String, Object>>() { }));
            Map<String, Object> policy = map(execution.spec.get("policy"));
            item.setConflictAction(text(policy.get("conflictPolicy"), "FAIL"));
            item.setSourceAction(text(policy.get("sourceSuccessAction"), "KEEP"));
            if (created) {
                itemMapper.insert(item);
            } else {
                itemMapper.updateById(item);
            }
        }
    }

    private void persistResults(FileTransferRunEntity run, PreparedTransfer prepared,
                                TransferResult result) {
        Map<String, TransferPlanItem> plans = new LinkedHashMap<String, TransferPlanItem>();
        for (TransferPlanItem plan : prepared.plan().items()) {
            plans.put(plan.itemId(), plan);
        }
        for (TransferItemResult value : result.items()) {
            TransferPlanItem plan = plans.get(value.itemId());
            LambdaUpdateWrapper<FileTransferRunItemEntity> update = new LambdaUpdateWrapper<FileTransferRunItemEntity>()
                    .set(FileTransferRunItemEntity::getStatus, value.status().name())
                    .set(FileTransferRunItemEntity::getTransferredBytes, value.transferredBytes())
                    .set(FileTransferRunItemEntity::getResumedBytes, value.resumedBytes())
                    .set(FileTransferRunItemEntity::getCurrentBytesPerSecond, 0L)
                    .set(FileTransferRunItemEntity::getSourceChecksum, value.sourceChecksum())
                    .set(FileTransferRunItemEntity::getTargetChecksum, value.targetChecksum())
                    .set(FileTransferRunItemEntity::getAttempts, value.attempts())
                    .set(FileTransferRunItemEntity::getErrorCode, value.errorCode())
                    .set(FileTransferRunItemEntity::getErrorMessage, value.errorMessage())
                    .set(FileTransferRunItemEntity::getStartedAt, local(value.startedAt()))
                    .set(FileTransferRunItemEntity::getEndedAt, local(value.finishedAt()))
                    .set(FileTransferRunItemEntity::getPostActionStatus,
                            value.status() == TransferItemStatus.POST_ACTION_FAILED ? "FAILED" : "SUCCESS")
                    .eq(FileTransferRunItemEntity::getRunId, run.getId())
                    .eq(FileTransferRunItemEntity::getCoreItemId, value.itemId());
            if (plan != null) {
                update.set(FileTransferRunItemEntity::getFileSize, plan.sourceSnapshot().size());
            }
            update.set(FileTransferRunItemEntity::getUpdatedAt, LocalDateTime.now());
            itemMapper.update(null, update);
        }
    }

    private void markStarted(FileTransferRunEntity run, Long runRecordId) {
        run.setRunRecordId(runRecordId);
        run.setStatus("RUNNING");
        run.setMessage("File transfer started");
        run.setStartedAt(LocalDateTime.now());
        run.setEndedAt(null);
        runMapper.updateById(run);
    }

    private void updatePlanTotals(FileTransferRunEntity run, long totalFiles, long totalBytes) {
        run.setTotalFiles(totalFiles);
        run.setTotalBytes(totalBytes);
        run.setActiveFiles((int) Math.min(Integer.MAX_VALUE, totalFiles));
        run.setMessage("File discovery completed");
        runMapper.updateById(run);
    }

    Map<String, Object> finish(FileTransferRunEntity run, List<TransferResult> results) {
        List<FileTransferRunItemEntity> items = itemMapper.selectList(
                new LambdaQueryWrapper<FileTransferRunItemEntity>()
                        .eq(FileTransferRunItemEntity::getRunId, run.getId()));
        long success = countStatus(items, "SUCCESS");
        long skipped = countStatus(items, "SKIPPED");
        long conflicts = countStatus(items, "CONFLICT");
        long postActionFailed = countStatus(items, "POST_ACTION_FAILED");
        long canceled = countStatus(items, "CANCELED");
        long failed = countStatus(items, "FAILED");
        long resumedFiles = items.stream().filter(item -> value(item.getResumedBytes()) > 0L).count();
        long transferred = items.stream().mapToLong(item -> value(item.getTransferredBytes())).sum();
        long resumed = items.stream().mapToLong(item -> value(item.getResumedBytes())).sum();
        long failedBytes = items.stream()
                .filter(item -> List.of("FAILED", "CONFLICT", "POST_ACTION_FAILED")
                        .contains(item.getStatus()))
                .mapToLong(item -> value(item.getFileSize())).sum();
        String finalStatus;
        if (isCanceled(run.getId()) || canceled > 0L) {
            finalStatus = "CANCELED";
        } else if (failed + conflicts + postActionFailed == 0L) {
            finalStatus = "SUCCESS";
        } else if (success + skipped > 0L || postActionFailed > 0L) {
            finalStatus = "PARTIAL_SUCCESS";
        } else {
            finalStatus = "FAILED";
        }
        run.setStatus(finalStatus);
        run.setSuccessFiles(success);
        run.setSkippedFiles(skipped);
        run.setFailedFiles(failed);
        run.setConflictFiles(conflicts);
        run.setResumedFiles(resumedFiles);
        run.setPostActionFailedFiles(postActionFailed);
        run.setTransferredBytes(transferred);
        run.setFailedBytes(failedBytes);
        run.setResumedBytes(resumed);
        run.setCurrentBytesPerSecond(0L);
        run.setActiveFiles(0);
        run.setEndedAt(LocalDateTime.now());
        run.setMessage("File transfer completed with status " + finalStatus);
        Map<String, Object> snapshot = copy(run.getResolvedSpecJson());
        snapshot.remove("retryCoreItemId");
        snapshot.remove("retryMode");
        run.setResolvedSpecJson(snapshot);
        runMapper.updateById(run);

        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("fileTransferRunId", run.getId());
        summary.put("fileTransferStatus", finalStatus);
        summary.put("status", "SUCCESS".equals(finalStatus) ? "SUCCESS" : "FAILED");
        summary.put("message", run.getMessage());
        summary.put("totalFiles", run.getTotalFiles());
        summary.put("successFiles", success);
        summary.put("skippedFiles", skipped);
        summary.put("failedFiles", failed);
        summary.put("conflictFiles", conflicts);
        summary.put("postActionFailedFiles", postActionFailed);
        summary.put("totalBytes", run.getTotalBytes());
        summary.put("transferredBytes", transferred);
        summary.put("resumedBytes", resumed);
        summary.put("channel", run.getChannel());
        Map<String, Object> metricSummary = new LinkedHashMap<String, Object>();
        metricSummary.put("collectedRecords", items.size());
        metricSummary.put("readSucceedRecords", success + skipped + postActionFailed);
        metricSummary.put("readFailedRecords", failed + conflicts);
        metricSummary.put("writeSucceedRecords", success + skipped + postActionFailed);
        metricSummary.put("writeFailedRecords", failed + conflicts);
        metricSummary.put("successRecords", success + skipped);
        metricSummary.put("failedRecords", failed + conflicts + postActionFailed);
        metricSummary.put("transformerTotalRecords", 0L);
        metricSummary.put("transformerSuccessRecords", 0L);
        metricSummary.put("transformerFailedRecords", 0L);
        metricSummary.put("transformerFilterRecords", 0L);
        summary.put("summary", metricSummary);
        return summary;
    }

    private void markFailed(FileTransferRunEntity run, String message) {
        if (isCanceled(run.getId())) {
            return;
        }
        runMapper.update(null, new LambdaUpdateWrapper<FileTransferRunEntity>()
                .set(FileTransferRunEntity::getStatus, "FAILED")
                .set(FileTransferRunEntity::getMessage,
                        message == null || message.isBlank() ? "File transfer failed" : message)
                .set(FileTransferRunEntity::getActiveFiles, 0)
                .set(FileTransferRunEntity::getCurrentBytesPerSecond, 0L)
                .set(FileTransferRunEntity::getEndedAt, LocalDateTime.now())
                .set(FileTransferRunEntity::getUpdatedAt, LocalDateTime.now())
                .eq(FileTransferRunEntity::getId, run.getId())
                .ne(FileTransferRunEntity::getStatus, "CANCELED"));
    }

    private boolean isCanceled(Long runId) {
        FileTransferRunEntity latest = runMapper.selectById(runId);
        return latest == null || "CANCELED".equalsIgnoreCase(latest.getStatus());
    }

    private long countStatus(List<FileTransferRunItemEntity> items, String status) {
        return items.stream().filter(item -> status.equalsIgnoreCase(item.getStatus())).count();
    }

    private long value(Long value) {
        return value == null ? 0L : value.longValue();
    }

    private LocalDateTime local(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneId.systemDefault());
    }

    private Optional<Long> optionalLong(Object value) {
        if (value instanceof Number) {
            return Optional.of(((Number) value).longValue());
        }
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Long.valueOf(String.valueOf(value).trim()));
    }

    private String text(Object value, String fallback) {
        return value == null || String.valueOf(value).trim().isEmpty()
                ? fallback : String.valueOf(value).trim();
    }

    private Map<String, Object> copy(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        return objectMapper.convertValue(values,
                new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            return new LinkedHashMap<String, Object>();
        }
        return objectMapper.convertValue(value,
                new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?>)) {
            return new ArrayList<Map<String, Object>>();
        }
        return objectMapper.convertValue(value,
                new TypeReference<ArrayList<Map<String, Object>>>() { });
    }

    private record TransferExecution(Long sourceClusterId, Long sourceDatasourceId,
                                     Long targetClusterId, Long targetDatasourceId,
                                     String direction, String channel,
                                     Map<String, Object> spec,
                                     TransferFileSystemFactory sourceFactory,
                                     TransferFileSystemFactory targetFactory) {
    }

    private record PreparedExecution(TransferExecution execution, PreparedTransfer prepared) {
    }
}
