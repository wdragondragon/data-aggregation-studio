package com.jdragon.studio.worker.filetransfer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.datasource.file.transfer.TransferFileSystem;
import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeSession;
import com.jdragon.aggregation.transfer.PreparedTransfer;
import com.jdragon.aggregation.transfer.TransferContractVersion;
import com.jdragon.aggregation.transfer.TransferFileSystemFactory;
import com.jdragon.aggregation.transfer.model.TransferPlan;
import com.jdragon.aggregation.transfer.model.TransferPlanItem;
import com.jdragon.aggregation.transfer.model.TransferResult;
import com.jdragon.aggregation.transfer.model.TransferSpec;
import com.jdragon.studio.core.spi.NodeExecutor;
import com.jdragon.studio.dto.enums.NodeType;
import com.jdragon.studio.dto.model.WorkflowNodeDefinition;
import com.jdragon.studio.worker.filetransfer.FileTransferExecutionAssembler.TransferExecution;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.mapper.FileTransferMetricSampleMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceClusterBindingService;
import com.jdragon.studio.infra.service.FileTransferStateMutationService;
import com.jdragon.studio.infra.service.StudioFileTransferContractAdapter;
import com.jdragon.studio.infra.service.UnstructuredManagementService;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import com.jdragon.studio.dto.enums.UnstructuredAclPermission;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
@Slf4j
public class FileTransferNodeExecutor implements NodeExecutor {

    private final FileTransferRunMapper runMapper;
    private final FileTransferRunItemMapper itemMapper;
    private final FileTransferMetricSampleMapper metricMapper;
    private final ObjectMapper objectMapper;
    private final FileTransferStateMutationService mutationService;
    private final StudioFileTransferContractAdapter contractAdapter;
    private final FileTransferEnginePort enginePort;
    private final FileTransferExecutionAssembler executionAssembler;
    private final FileTransferPlanPersistenceService planPersistenceService;
    private final FileTransferResultPersistenceService resultPersistenceService;
    private final FileTransferPostActionRetryService postActionRetryService;
    private UnstructuredManagementService unstructuredManagementService;

    public FileTransferNodeExecutor(FileTransferRunMapper runMapper,
                                    FileTransferRunItemMapper itemMapper,
                                    FileTransferMetricSampleMapper metricMapper,
                                    DataSourceService dataSourceService,
                                    DatasourceClusterBindingService datasourceClusterBindingService,
                                    AggregationSourceCapabilityProvider sourceCapabilityProvider,
                                    ObjectMapper objectMapper,
                                    FileTransferStateMutationService mutationService) {
        this(runMapper, itemMapper, metricMapper, dataSourceService, datasourceClusterBindingService,
                sourceCapabilityProvider, objectMapper, mutationService,
                new StudioFileTransferContractAdapter(), new DataAggregationFileTransferEngineAdapter());
    }

    @Autowired
    public FileTransferNodeExecutor(FileTransferRunMapper runMapper,
                                    FileTransferRunItemMapper itemMapper,
                                    FileTransferMetricSampleMapper metricMapper,
                                    DataSourceService dataSourceService,
                                    DatasourceClusterBindingService datasourceClusterBindingService,
                                    AggregationSourceCapabilityProvider sourceCapabilityProvider,
                                    ObjectMapper objectMapper,
                                    FileTransferStateMutationService mutationService,
                                    StudioFileTransferContractAdapter contractAdapter,
                                    FileTransferEnginePort enginePort) {
        this.runMapper = runMapper;
        this.itemMapper = itemMapper;
        this.metricMapper = metricMapper;
        this.objectMapper = objectMapper;
        this.mutationService = mutationService;
        this.contractAdapter = contractAdapter;
        this.enginePort = enginePort;
        this.executionAssembler = new FileTransferExecutionAssembler(dataSourceService,
                datasourceClusterBindingService, sourceCapabilityProvider, objectMapper);
        this.planPersistenceService = new FileTransferPlanPersistenceService(
                itemMapper, mutationService, objectMapper);
        this.resultPersistenceService = new FileTransferResultPersistenceService(mutationService);
        this.postActionRetryService = new FileTransferPostActionRetryService(itemMapper, objectMapper);
    }

    FileTransferNodeExecutor(FileTransferRunMapper runMapper,
                             FileTransferRunItemMapper itemMapper,
                             FileTransferMetricSampleMapper metricMapper,
                             DataSourceService dataSourceService,
                             DatasourceClusterBindingService datasourceClusterBindingService,
                             AggregationSourceCapabilityProvider sourceCapabilityProvider,
                             ObjectMapper objectMapper) {
        this(runMapper, itemMapper, metricMapper, dataSourceService, datasourceClusterBindingService,
                sourceCapabilityProvider, objectMapper, null);
    }

    /** Optional for focused unit tests; production wiring supplies the ACL service. */
    @Autowired(required = false)
    void setUnstructuredManagementService(UnstructuredManagementService service) {
        this.unstructuredManagementService = service;
    }

    @Override
    public boolean supports(WorkflowNodeDefinition definition) {
        return definition != null && definition.getNodeType() == NodeType.FILE_TRANSFER;
    }

    @Override
    public Map<String, Object> execute(WorkflowNodeDefinition definition,
                                       Map<String, Object> runtimeContext) {
        setPhase(runtimeContext, "LOAD_RUN");
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

        setPhase(runtimeContext, "RESOLVE_SPEC");
        Map<String, Object> runSnapshot = copy(run.getResolvedSpecJson());
        boolean snapshotChanged = false;
        if (optionalLong(runSnapshot.get("plannedAtMillis")).isEmpty()) {
            runSnapshot.put("plannedAtMillis", ensurePlannedAtMillis(runSnapshot,
                    Instant.now().toEpochMilli()));
            snapshotChanged = true;
        }
        if (optionalLong(runSnapshot.get("contractVersion")).isEmpty()) {
            runSnapshot.put("contractVersion", TransferContractVersion.CURRENT);
            snapshotChanged = true;
        }
        if (snapshotChanged) {
            run.setResolvedSpecJson(runSnapshot);
            updateRun(run.getId(), new LambdaUpdateWrapper<FileTransferRunEntity>()
                    .set(FileTransferRunEntity::getResolvedSpecJson, runSnapshot,
                            "typeHandler=" + JacksonTypeHandler.class.getCanonicalName())
                    .eq(FileTransferRunEntity::getId, run.getId()), false, true);
        }
        String retryCoreItemId = text(runSnapshot.get("retryCoreItemId"), null);
        String retryMode = text(runSnapshot.get("retryMode"), "TRANSFER");
        boolean postActionOnly = "POST_ACTION_ONLY".equalsIgnoreCase(retryMode);
        List<TransferExecution> executions = buildExecutions(run, runSnapshot);
        StudioTransferEventListener listener = new StudioTransferEventListener(
                run, runMapper, itemMapper, metricMapper, mutationService);
        List<TransferResult> results = new ArrayList<TransferResult>();
        try {
            try (PluginRuntimeSession pluginSession = PluginRuntimeSession.open()) {
                Map<String, String> transferMdc = MDC.getCopyOfContextMap();
                List<PreparedExecution> preparedExecutions = new ArrayList<PreparedExecution>();
                long totalFiles = 0L;
                long totalBytes = 0L;
                int index = 0;
                boolean retryItemMatched = retryCoreItemId == null;
                for (TransferExecution execution : executions) {
                    setPhase(runtimeContext, "PLAN");
                    String coreRunId = run.getId() + "-" + (++index);
                    TransferSpec spec = contractAdapter.map(execution.spec);
                    long planStartedAt = System.nanoTime();
                    log.info("[FT_PLAN_START] 开始生成文件传输计划 runId={} runRecordId={} batch={} "
                                    + "sourceDatasourceId={} sourceType={} targetDatasourceId={} targetType={}",
                            run.getId(), runRecordId, index,
                            execution.sourceDatasourceId, endpointType(execution, "source"),
                            execution.targetDatasourceId, endpointType(execution, "target"));
                    PreparedTransfer prepared;
                    if (postActionOnly) {
                        preflightResolvedPaths(execution, spec, coreRunId, plannedAt(runSnapshot));
                        Optional<PreparedTransfer> retryPrepared = preparePostActionRetry(
                                run, execution, spec, coreRunId, retryCoreItemId, runSnapshot);
                        if (retryPrepared.isEmpty()) {
                            continue;
                        }
                        prepared = retryPrepared.get();
                    } else {
                        preflightResolvedPaths(execution, spec, coreRunId, plannedAt(runSnapshot));
                        try (TransferFileSystem source = execution.sourceFactory.open();
                             TransferFileSystem target = execution.targetFactory.open()) {
                            prepared = enginePort.prepare(spec, source, target, coreRunId,
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
                    long plannedFiles = prepared.plan().items().size();
                    long plannedBytes = prepared.plan().items().stream()
                            .mapToLong(item -> item.sourceSnapshot().size()).sum();
                    totalFiles += plannedFiles;
                    totalBytes += plannedBytes;
                    log.info("[FT_PLAN_COMPLETED] 文件传输计划生成完成 runId={} runRecordId={} batch={} "
                                    + "files={} totalBytes={} durationMillis={}",
                            run.getId(), runRecordId, index, plannedFiles, plannedBytes,
                            elapsedMillis(planStartedAt));
                }
                if (!retryItemMatched) {
                    throw new IllegalStateException("Retry file transfer item is not part of the current run plan");
                }
                requireManualTransferFiles(runSnapshot, retryCoreItemId, totalFiles);
                if (retryCoreItemId == null) {
                    updatePlanTotals(run, totalFiles, totalBytes);
                }
                if (!preparedExecutions.isEmpty()) {
                    PreparedTransfer first = preparedExecutions.get(0).prepared;
                    log.info("[FT_RUN_START] 文件传输运行开始 runId={} runRecordId={} taskId={} "
                                    + "runtimeClusterId={} batches={} files={} totalBytes={} concurrency={} "
                                    + "verificationMode={} conflictPolicy={} checkpointRecoveryMode={}",
                            run.getId(), runRecordId, run.getTaskId(), run.getRuntimeClusterId(),
                            preparedExecutions.size(), totalFiles, totalBytes,
                            first.resolvedSpec().runtime().concurrency(),
                            first.resolvedSpec().policy().verificationMode(),
                            first.resolvedSpec().policy().conflictPolicy(),
                            first.resolvedSpec().runtime().checkpointRecoveryMode());
                }
                for (PreparedExecution preparedExecution : preparedExecutions) {
                    if (isCanceled(run.getId())) {
                        break;
                    }
                    setPhase(runtimeContext, "TRANSFER");
                    TransferResult result = postActionOnly
                            ? retryPostAction(run, preparedExecution.prepared,
                                    preparedExecution.execution.sourceFactory,
                                    preparedExecution.execution.targetFactory)
                            : enginePort.execute(preparedExecution.prepared,
                                    preparedExecution.execution.sourceFactory,
                                    preparedExecution.execution.targetFactory,
                                     new StudioTransferCheckpointStore(run.getId(), itemMapper,
                                             mutationService, objectMapper),
                                     listener,
                                     new DatabaseTransferControl(run.getId(), runMapper),
                                     task -> bindTransferContext(pluginSession, transferMdc, task));
                    results.add(result);
                    setPhase(runtimeContext, "PERSIST_RESULT");
                    persistResults(run, preparedExecution.prepared, result);
                }
                runtimeContext.put("pluginRevisions", pluginSession.revisions());
            }
            setPhase(runtimeContext, "FINISH");
            return finish(run, results);
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
    }

    private List<TransferExecution> buildExecutions(FileTransferRunEntity run,
                                                     Map<String, Object> snapshot) {
        return executionAssembler.buildExecutions(run, snapshot);
    }

    private List<TransferExecution> manualExecutions(FileTransferRunEntity run,
                                                      Map<String, Object> snapshot,
                                                      List<Map<String, Object>> items) {
        return executionAssembler.manualExecutions(run, snapshot, items);
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
        return postActionRetryService.prepare(
                run, execution, spec, coreRunId, retryCoreItemId, snapshot);
    }

    TransferResult retryPostAction(FileTransferRunEntity run,
                                   PreparedTransfer prepared,
                                   TransferFileSystemFactory sourceFactory,
                                   TransferFileSystemFactory targetFactory) {
        return postActionRetryService.retry(run, prepared, sourceFactory, targetFactory);
    }

    private void persistResolvedTaskSpec(FileTransferRunEntity run, PreparedTransfer prepared) {
        planPersistenceService.persistResolvedTaskSpec(run, prepared);
    }

    private Instant plannedAt(Map<String, Object> snapshot) {
        return optionalLong(snapshot == null ? null : snapshot.get("plannedAtMillis"))
                .map(Instant::ofEpochMilli)
                .orElseGet(Instant::now);
    }

    /**
     * Assigns the first execution timestamp only when the run snapshot does not
     * already contain one. Keeping this mutation isolated makes the retry and
     * worker-recovery contract explicit and deterministic.
     */
    static long ensurePlannedAtMillis(Map<String, Object> snapshot, long fallbackMillis) {
        if (snapshot == null) {
            throw new IllegalArgumentException("run snapshot is required");
        }
        Object existing = snapshot.get("plannedAtMillis");
        if (existing instanceof Number number) {
            return number.longValue();
        }
        if (existing != null && !String.valueOf(existing).trim().isEmpty()) {
            return Long.parseLong(String.valueOf(existing).trim());
        }
        snapshot.put("plannedAtMillis", fallbackMillis);
        return fallbackMillis;
    }

    private void preflightResolvedPaths(TransferExecution execution, TransferSpec spec,
                                        String coreRunId, Instant plannedAt) {
        TransferSpec resolved = new com.jdragon.aggregation.transfer.DynamicTemplateResolver()
                .resolve(spec, coreRunId, plannedAt);
        String sourcePath = resolved.selection().rootPath();
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new IllegalArgumentException("Resolved selection.rootPath is empty");
        }
        String targetPath = resolved.mapping().targetRootPath();
        if (targetPath == null || targetPath.isBlank()) {
            throw new IllegalArgumentException("Resolved mapping.targetRootPath is empty");
        }
        if (unstructuredManagementService != null) {
            unstructuredManagementService.assertPermission(execution.sourceClusterId,
                    execution.sourceDatasourceId, sourcePath, UnstructuredAclPermission.DOWNLOAD);
            unstructuredManagementService.assertPermission(execution.targetClusterId,
                    execution.targetDatasourceId, targetPath, UnstructuredAclPermission.EDIT);
        }
    }

    private void persistPlan(FileTransferRunEntity run, TransferExecution execution,
                             PreparedTransfer prepared) {
        planPersistenceService.persistPlan(run, execution, prepared);
    }

    private void persistResults(FileTransferRunEntity run, PreparedTransfer prepared,
                                TransferResult result) {
        resultPersistenceService.persistResults(run, prepared, result);
    }

    private void markStarted(FileTransferRunEntity run, Long runRecordId) {
        run.setRunRecordId(runRecordId);
        run.setStatus("RUNNING");
        run.setMessage("File transfer started");
        run.setStartedAt(LocalDateTime.now());
        run.setEndedAt(null);
        updateRun(run.getId(), new LambdaUpdateWrapper<FileTransferRunEntity>()
                .set(FileTransferRunEntity::getRunRecordId, runRecordId)
                .set(FileTransferRunEntity::getStatus, "RUNNING")
                .set(FileTransferRunEntity::getMessage, run.getMessage())
                .set(FileTransferRunEntity::getStartedAt, run.getStartedAt())
                .set(FileTransferRunEntity::getEndedAt, null)
                .eq(FileTransferRunEntity::getId, run.getId())
                .in(FileTransferRunEntity::getStatus, "QUEUED", "RUNNING"), false, true);
    }

    private void updatePlanTotals(FileTransferRunEntity run, long totalFiles, long totalBytes) {
        planPersistenceService.updatePlanTotals(run, totalFiles, totalBytes);
    }

    Map<String, Object> finish(FileTransferRunEntity run, List<TransferResult> results) {
        // Progress samples are persisted through field-level updates while the
        // executor still holds the run entity from dispatch time. Reload it
        // before the terminal write so peak throughput and other live fields
        // cannot be overwritten by stale zero values.
        FileTransferRunEntity latest = runMapper.selectById(run.getId());
        if (latest != null) {
            run = latest;
        }
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
        updateRun(run.getId(), new LambdaUpdateWrapper<FileTransferRunEntity>()
                .set(FileTransferRunEntity::getStatus, finalStatus)
                .set(FileTransferRunEntity::getSuccessFiles, success)
                .set(FileTransferRunEntity::getSkippedFiles, skipped)
                .set(FileTransferRunEntity::getFailedFiles, failed)
                .set(FileTransferRunEntity::getConflictFiles, conflicts)
                .set(FileTransferRunEntity::getResumedFiles, resumedFiles)
                .set(FileTransferRunEntity::getPostActionFailedFiles, postActionFailed)
                .set(FileTransferRunEntity::getTransferredBytes, transferred)
                .set(FileTransferRunEntity::getFailedBytes, failedBytes)
                .set(FileTransferRunEntity::getResumedBytes, resumed)
                .set(FileTransferRunEntity::getCurrentBytesPerSecond, 0L)
                .set(FileTransferRunEntity::getActiveFiles, 0)
                .set(FileTransferRunEntity::getEndedAt, run.getEndedAt())
                .set(FileTransferRunEntity::getMessage, run.getMessage())
                .set(FileTransferRunEntity::getResolvedSpecJson, snapshot,
                        "typeHandler=" + JacksonTypeHandler.class.getCanonicalName())
                .eq(FileTransferRunEntity::getId, run.getId()), false, true);

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

        long durationMillis = run.getStartedAt() == null || run.getEndedAt() == null
                ? 0L : Math.max(0L, Duration.between(run.getStartedAt(), run.getEndedAt()).toMillis());
        if ("PARTIAL_SUCCESS".equals(finalStatus)) {
            log.warn("[FT_RUN_PARTIAL] 文件传输部分成功 runId={} runRecordId={} successFiles={} "
                            + "skippedFiles={} failedFiles={} conflictFiles={} postActionFailedFiles={}",
                    run.getId(), run.getRunRecordId(), success, skipped, failed, conflicts, postActionFailed);
        }
        log.info("[FT_RUN_COMPLETED] 文件传输运行结束 runId={} runRecordId={} status={} "
                        + "successFiles={} skippedFiles={} failedFiles={} conflictFiles={} resumedFiles={} "
                        + "transferredBytes={} totalBytes={} peakBytesPerSecond={} durationMillis={}",
                run.getId(), run.getRunRecordId(), finalStatus, success, skipped, failed, conflicts,
                resumedFiles, transferred, value(run.getTotalBytes()), value(run.getPeakBytesPerSecond()),
                durationMillis);
        return summary;
    }

    private void markFailed(FileTransferRunEntity run, String message) {
        if (isPausedOrCanceled(run.getId())) {
            return;
        }
        updateRun(run.getId(), new LambdaUpdateWrapper<FileTransferRunEntity>()
                .set(FileTransferRunEntity::getStatus, "FAILED")
                .set(FileTransferRunEntity::getMessage,
                        message == null || message.isBlank() ? "File transfer failed" : message)
                .set(FileTransferRunEntity::getActiveFiles, 0)
                .set(FileTransferRunEntity::getCurrentBytesPerSecond, 0L)
                .set(FileTransferRunEntity::getEndedAt, LocalDateTime.now())
                .set(FileTransferRunEntity::getUpdatedAt, LocalDateTime.now())
                .eq(FileTransferRunEntity::getId, run.getId())
                .notIn(FileTransferRunEntity::getStatus, "CANCELED", "PAUSED"), false, true);
    }

    private boolean isPausedOrCanceled(Long runId) {
        FileTransferRunEntity latest = runMapper.selectById(runId);
        return latest == null
                || "CANCELED".equalsIgnoreCase(latest.getStatus())
                || "PAUSED".equalsIgnoreCase(latest.getStatus());
    }

    private boolean isCanceled(Long runId) {
        FileTransferRunEntity latest = runMapper.selectById(runId);
        return latest == null || "CANCELED".equalsIgnoreCase(latest.getStatus());
    }

    private void updateRun(Long runId, LambdaUpdateWrapper<FileTransferRunEntity> update,
                           boolean progress, boolean force) {
        mutationService().updateRunAndEvent(runId, update, progress, force);
    }

    private FileTransferStateMutationService mutationService() {
        if (mutationService == null) {
            throw new IllegalStateException("File transfer state mutation service is required");
        }
        return mutationService;
    }

    private void setPhase(Map<String, Object> runtimeContext, String phase) {
        if (runtimeContext != null) {
            runtimeContext.put("fileTransferPhase", phase);
        }
    }

    static Runnable bindTransferContext(PluginRuntimeSession pluginSession,
                                        Map<String, String> context,
                                        Runnable task) {
        return pluginSession.bind(() -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (context == null || context.isEmpty()) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(context);
                }
                task.run();
            } finally {
                if (previous == null || previous.isEmpty()) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        });
    }

    private String endpointType(TransferExecution execution, String endpoint) {
        return text(map(execution.spec.get(endpoint)).get("plugin"), "UNKNOWN");
    }

    private long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private long countStatus(List<FileTransferRunItemEntity> items, String status) {
        return items.stream().filter(item -> status.equalsIgnoreCase(item.getStatus())).count();
    }

    private long value(Long value) {
        return value == null ? 0L : value.longValue();
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

    private record PreparedExecution(TransferExecution execution, PreparedTransfer prepared) {
    }
}
