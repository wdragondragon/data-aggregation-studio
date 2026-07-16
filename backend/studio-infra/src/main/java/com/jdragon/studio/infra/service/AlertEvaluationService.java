package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.dto.enums.AlertRuleType;
import com.jdragon.studio.dto.enums.AlertSubjectType;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.AlertIncidentEntity;
import com.jdragon.studio.infra.entity.AlertRuleEntity;
import com.jdragon.studio.infra.entity.DataIngestionAccessLogEntity;
import com.jdragon.studio.infra.entity.DataServiceAccessLogEntity;
import com.jdragon.studio.infra.entity.ProtocolConversionAccessLogEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.AlertIncidentMapper;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataIngestionAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataIngestionAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.ProjectWorkerBindingMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionAccessCounterMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionAccessLogMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.QualityTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.model.AlertObservation;
import com.jdragon.studio.infra.model.AlertSignal;
import com.jdragon.studio.infra.model.WorkflowRunOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
public class AlertEvaluationService {

    private final AlertRuleService alertRuleService;
    private final AlertIncidentService alertIncidentService;
    private final AlertIncidentMapper alertIncidentMapper;
    private final StudioPlatformProperties properties;
    private final RunRecordMapper runRecordMapper;
    private final DataServiceAccessLogMapper dataServiceAccessLogMapper;
    private final DataIngestionAccessLogMapper dataIngestionAccessLogMapper;
    private final ProtocolConversionAccessLogMapper protocolConversionAccessLogMapper;
    private final AlertPeriodicObservationProvider observationProvider;

    public AlertEvaluationService(AlertRuleService alertRuleService,
                                  AlertIncidentService alertIncidentService,
                                  AlertIncidentMapper alertIncidentMapper,
                                  StudioPlatformProperties properties,
                                  RunRecordMapper runRecordMapper,
                                  DispatchTaskMapper dispatchTaskMapper,
                                  WorkerLeaseMapper workerLeaseMapper,
                                  ProjectWorkerBindingMapper projectWorkerBindingMapper,
                                  CollectionTaskDefinitionMapper collectionTaskDefinitionMapper,
                                  QualityTaskDefinitionMapper qualityTaskDefinitionMapper,
                                  WorkflowDefinitionMapper workflowDefinitionMapper,
                                  DataServiceDefinitionMapper dataServiceDefinitionMapper,
                                  DataIngestionServiceMapper dataIngestionServiceMapper,
                                  ProtocolConversionServiceMapper protocolConversionServiceMapper,
                                  DataServiceAccessCounterMapper dataServiceAccessCounterMapper,
                                  DataIngestionAccessCounterMapper dataIngestionAccessCounterMapper,
                                  ProtocolConversionAccessCounterMapper protocolConversionAccessCounterMapper,
                                  DataServiceAccessLogMapper dataServiceAccessLogMapper,
                                  DataIngestionAccessLogMapper dataIngestionAccessLogMapper,
                                  ProtocolConversionAccessLogMapper protocolConversionAccessLogMapper) {
        this.alertRuleService = alertRuleService;
        this.alertIncidentService = alertIncidentService;
        this.alertIncidentMapper = alertIncidentMapper;
        this.properties = properties;
        this.runRecordMapper = runRecordMapper;
        this.dataServiceAccessLogMapper = dataServiceAccessLogMapper;
        this.dataIngestionAccessLogMapper = dataIngestionAccessLogMapper;
        this.protocolConversionAccessLogMapper = protocolConversionAccessLogMapper;
        this.observationProvider = new AlertPeriodicObservationProvider(runRecordMapper, dispatchTaskMapper,
                workerLeaseMapper, projectWorkerBindingMapper, collectionTaskDefinitionMapper,
                qualityTaskDefinitionMapper, workflowDefinitionMapper, dataServiceDefinitionMapper,
                dataIngestionServiceMapper, protocolConversionServiceMapper, dataServiceAccessCounterMapper,
                dataIngestionAccessCounterMapper, protocolConversionAccessCounterMapper);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void evaluateSignal(AlertSignal signal) {
        if (!enabled() || !properties.getAlert().isEvaluationEnabled() || signal == null) {
            return;
        }
        if ("RULE_ACTIVATED".equalsIgnoreCase(signal.getSignalType())) {
            evaluateActivatedRule(signal);
            return;
        }
        if (signal.getProjectId() == null || !StringUtils.hasText(signal.getSubjectType())) {
            return;
        }
        if ("EXECUTION".equalsIgnoreCase(signal.getSignalType())) {
            evaluateExecutionSignal(signal);
        } else if ("INVOCATION".equalsIgnoreCase(signal.getSignalType())) {
            evaluateInvocationSignal(signal);
        } else if ("LOG_ARCHIVE".equalsIgnoreCase(signal.getSignalType())) {
            evaluateLogSignal(signal);
        }
    }

    private void evaluateActivatedRule(AlertSignal signal) {
        Optional<Long> ruleId = longValue(signal.getSourceId());
        if (ruleId.isEmpty() || signal.getProjectId() == null || !StringUtils.hasText(signal.getTenantId())
                || !StringUtils.hasText(signal.getStatus()) || !StringUtils.hasText(signal.getSubjectType())) {
            return;
        }
        for (AlertRuleEntity rule : alertRuleService.enabledRules(signal.getTenantId(), signal.getProjectId(),
                signal.getStatus(), signal.getSubjectType(), signal.getSubjectId())) {
            if (!ruleId.get().equals(rule.getId())) {
                continue;
            }
            try {
                AlertRuleType type = AlertRuleType.valueOf(rule.getRuleType());
                if (!isPeriodic(type)) {
                    return;
                }
                evaluatePeriodicRule(rule, type);
                markEvaluationSafely(rule, "SUCCESS", null);
            } catch (Exception ex) {
                log.warn("Activated alert rule evaluation failed for {}", rule.getId(), ex);
                markEvaluationSafely(rule, "ERROR", ex.getMessage());
            }
            return;
        }
    }

    public void evaluateAll() {
        if (!enabled() || !properties.getAlert().isEvaluationEnabled()) {
            return;
        }
        for (AlertRuleEntity rule : alertRuleService.enabledRulesForEvaluation()) {
            try {
                AlertRuleType type = AlertRuleType.valueOf(rule.getRuleType());
                if (!isPeriodic(type)) {
                    continue;
                }
                evaluatePeriodicRule(rule, type);
                markEvaluationSafely(rule, "SUCCESS", null);
            } catch (Exception ex) {
                log.warn("Alert rule evaluation failed for {}", rule.getId(), ex);
                markEvaluationSafely(rule, "ERROR", ex.getMessage());
            }
        }
    }

    private void evaluateExecutionSignal(AlertSignal signal) {
        Map<AlertRuleType, List<AlertRuleEntity>> rulesByType = new LinkedHashMap<AlertRuleType, List<AlertRuleEntity>>();
        List<AlertRuleEntity> matchingRules = new ArrayList<AlertRuleEntity>();
        for (AlertRuleType type : Arrays.asList(AlertRuleType.EXECUTION_FAILED, AlertRuleType.CONSECUTIVE_FAILURES)) {
            List<AlertRuleEntity> rules = alertRuleService.enabledRules(signal.getTenantId(), signal.getProjectId(),
                    type.name(), signal.getSubjectType(), signal.getSubjectId());
            rulesByType.put(type, rules);
            matchingRules.addAll(rules);
        }
        if (matchingRules.isEmpty()) {
            return;
        }
        try {
            if (isSupersededExecutionSignal(signal)) {
                return;
            }
        } catch (Exception ex) {
            log.warn("Alert execution signal freshness evaluation failed for source {}", signal.getSourceEventKey(), ex);
            for (AlertRuleEntity rule : matchingRules) {
                markEvaluationSafely(rule, "ERROR", ex.getMessage());
            }
            return;
        }
        for (Map.Entry<AlertRuleType, List<AlertRuleEntity>> entry : rulesByType.entrySet()) {
            AlertRuleType type = entry.getKey();
            List<AlertRuleEntity> rules = entry.getValue();
            for (AlertRuleEntity rule : rules) {
                evaluateSignalRule(rule, signal, () -> {
                    boolean active = !signal.isSuccess() && ("FAILED".equalsIgnoreCase(signal.getStatus())
                            || "ERROR".equalsIgnoreCase(signal.getStatus()));
                    Map<String, Object> evidence = copy(signal.getEvidence());
                    if (type == AlertRuleType.CONSECUTIVE_FAILURES) {
                        int consecutive = signal.isSuccess() ? 0 : consecutiveFailures(signal);
                        evidence.put("consecutiveFailures", consecutive);
                        active = consecutive >= intCondition(rule, "consecutiveCount", 3);
                    }
                    AlertObservation observation = fromSignal(signal, active)
                            .setSummary(active
                                    ? rule.getName() + "：" + signal.getSubjectName() + " " + failureSummary(type, evidence)
                                    : rule.getName() + "：" + signal.getSubjectName() + " 已恢复")
                            .setEvidence(evidence);
                    alertIncidentService.recordCondition(rule, observation);
                });
            }
        }
    }

    private void evaluateInvocationSignal(AlertSignal signal) {
        List<AlertRuleEntity> rules = alertRuleService.enabledRules(signal.getTenantId(), signal.getProjectId(),
                AlertRuleType.INVOCATION_WRITE_FAILED.name(), signal.getSubjectType(), signal.getSubjectId());
        if (rules.isEmpty()) {
            return;
        }
        try {
            if (isSupersededInvocationSignal(signal)) {
                return;
            }
        } catch (Exception ex) {
            log.warn("Alert invocation signal freshness evaluation failed for source {}", signal.getSourceEventKey(), ex);
            for (AlertRuleEntity rule : rules) {
                markEvaluationSafely(rule, "ERROR", ex.getMessage());
            }
            return;
        }
        for (AlertRuleEntity rule : rules) {
            evaluateSignalRule(rule, signal, () -> {
                boolean active = !signal.isSuccess()
                        || (signal.getFailureCount() != null && signal.getFailureCount().longValue() > 0L);
                AlertObservation observation = fromSignal(signal, active)
                        .setSummary(active
                                ? rule.getName() + "：" + signal.getSubjectName() + " 调用或写入失败"
                                : rule.getName() + "：" + signal.getSubjectName() + " 调用已恢复");
                alertIncidentService.recordCondition(rule, observation);
            });
        }
    }

    private void evaluateLogSignal(AlertSignal signal) {
        List<AlertRuleEntity> rules = alertRuleService.enabledRules(signal.getTenantId(), signal.getProjectId(),
                AlertRuleType.LOG_UPLOAD_FAILED.name(), AlertSubjectType.LOG_STORAGE.name(), null);
        String domain = signal.getSubjectKey();
        List<AlertRuleEntity> matchingRules = new ArrayList<AlertRuleEntity>();
        for (AlertRuleEntity rule : rules) {
            if (stringListCondition(rule, "domains").contains(domain)) {
                matchingRules.add(rule);
            }
        }
        if (matchingRules.isEmpty()) {
            return;
        }
        LogState latestState;
        try {
            latestState = latestLogState(signal.getTenantId(), signal.getProjectId(), domain);
        } catch (Exception ex) {
            log.warn("Alert log signal freshness evaluation failed for source {}", signal.getSourceEventKey(), ex);
            for (AlertRuleEntity rule : matchingRules) {
                markEvaluationSafely(rule, "ERROR", ex.getMessage());
            }
            return;
        }
        if (isSupersededLogSignal(signal, latestState)) {
            return;
        }
        for (AlertRuleEntity rule : matchingRules) {
            evaluateSignalRule(rule, signal, () -> {
                boolean active = "FAILED".equalsIgnoreCase(signal.getStatus())
                        || "UPLOAD_FAILED".equalsIgnoreCase(signal.getStatus());
                boolean recovered = "AVAILABLE".equalsIgnoreCase(signal.getStatus());
                if (!active && !recovered) {
                    return;
                }
                AlertObservation observation = fromSignal(signal, active)
                        .setSubjectType(AlertSubjectType.LOG_STORAGE.name())
                        .setSubjectId(null)
                        .setSubjectKey(domain)
                        .setSubjectName(logDomainName(domain))
                        .setTargetPath(AlertIncidentService.targetPath(AlertSubjectType.LOG_STORAGE.name(), null, null))
                        .setSummary(active ? logDomainName(domain) + "上传失败" : logDomainName(domain) + "上传已恢复");
                alertIncidentService.recordCondition(rule, observation);
            });
        }
    }

    private void evaluateSignalRule(AlertRuleEntity rule, AlertSignal signal, Runnable evaluator) {
        try {
            evaluator.run();
            markEvaluationSafely(rule, "SUCCESS", null);
        } catch (Exception ex) {
            log.warn("Alert signal rule evaluation failed for rule {} and source {}",
                    rule.getId(), signal.getSourceEventKey(), ex);
            markEvaluationSafely(rule, "ERROR", ex.getMessage());
        }
    }

    private void markEvaluationSafely(AlertRuleEntity rule, String status, String error) {
        try {
            alertRuleService.markEvaluation(rule, status, error);
        } catch (Exception ex) {
            log.warn("Alert rule evaluation result could not be persisted for {}: {}",
                    rule == null ? null : rule.getId(), ex.getClass().getSimpleName());
        }
    }

    private void evaluatePeriodicRule(AlertRuleEntity rule, AlertRuleType type) {
        List<AlertObservation> observations;
        boolean recoverMissing = true;
        Set<String> configuredLogDomains = Collections.emptySet();
        switch (type) {
            case RUN_TIMEOUT:
                observations = observationProvider.timeoutObservations(rule);
                break;
            case SERVICE_FAILURE_RATE:
                observations = observationProvider.failureRateObservations(rule);
                break;
            case WORKER_OFFLINE:
                observations = observationProvider.workerObservations(rule);
                break;
            case QUEUE_BACKLOG:
                observations = observationProvider.queueObservations(rule);
                break;
            case SCHEDULE_DELAY:
                observations = observationProvider.scheduleDelayObservations(rule);
                break;
            case LOG_UPLOAD_FAILED:
                observations = logObservations(rule);
                recoverMissing = false;
                configuredLogDomains = new LinkedHashSet<String>(stringListCondition(rule, "domains"));
                break;
            default:
                return;
        }
        Set<String> evaluatedKeys = new LinkedHashSet<String>();
        Set<String> activeKeys = new LinkedHashSet<String>();
        for (AlertObservation observation : observations) {
            evaluatedKeys.add(observation.getSubjectKey());
            if (observation.isActive()) {
                activeKeys.add(observation.getSubjectKey());
            }
            alertIncidentService.recordCondition(rule, observation);
        }
        List<AlertIncidentEntity> activeIncidents = alertIncidentMapper.selectList(new LambdaQueryWrapper<AlertIncidentEntity>()
                .eq(AlertIncidentEntity::getTenantId, rule.getTenantId())
                .eq(AlertIncidentEntity::getProjectId, rule.getProjectId())
                .eq(AlertIncidentEntity::getRuleId, rule.getId())
                .eq(AlertIncidentEntity::getConditionActive, 1));
        for (AlertIncidentEntity incident : activeIncidents) {
            boolean shouldRecover = !activeKeys.contains(incident.getSubjectKey())
                    && (recoverMissing || evaluatedKeys.contains(incident.getSubjectKey())
                    || (type == AlertRuleType.LOG_UPLOAD_FAILED
                    && !configuredLogDomains.contains(incident.getSubjectKey())));
            if (!shouldRecover) {
                continue;
            }
            alertIncidentService.recordCondition(rule, new AlertObservation()
                    .setActive(false)
                    .setSubjectType(incident.getSubjectType())
                    .setSubjectKey(incident.getSubjectKey())
                    .setSubjectId(incident.getSubjectId())
                    .setSubjectName(incident.getSubjectNameSnapshot())
                    .setTargetPath(incident.getTargetPath())
                    .setSummary(rule.getName() + "：" + incident.getSubjectNameSnapshot() + " 已恢复")
                    .setSourceType("SCHEDULED_EVALUATION")
                    .setObservedAt(LocalDateTime.now()));
        }
    }

    private List<AlertObservation> logObservations(AlertRuleEntity rule) {
        List<AlertObservation> result = new ArrayList<AlertObservation>();
        for (String domain : stringListCondition(rule, "domains")) {
            LogState state = latestLogState(rule, domain);
            if (state == null || (!state.failed && !state.available)) {
                continue;
            }
            Map<String, Object> evidence = new LinkedHashMap<String, Object>();
            evidence.put("domain", domain);
            evidence.put("recordId", state.sourceId);
            evidence.put("status", state.status);
            evidence.put("error", state.error);
            result.add(new AlertObservation()
                    .setActive(state.failed)
                    .setSubjectType(rule.getSubjectType())
                    .setSubjectKey(domain)
                    .setSubjectName(logDomainName(domain))
                    .setTargetPath(AlertIncidentService.targetPath(AlertSubjectType.LOG_STORAGE.name(), null, null))
                    .setSummary(state.failed ? logDomainName(domain) + "上传失败" : logDomainName(domain) + "上传已恢复")
                    .setEvidence(evidence)
                    .setSourceType("SCHEDULED_EVALUATION")
                    .setSourceId(state.sourceId)
                    .setObservedAt(LocalDateTime.now()));
        }
        return result;
    }

    private LogState latestLogState(AlertRuleEntity rule, String domain) {
        return latestLogState(rule.getTenantId(), rule.getProjectId(), domain);
    }

    private LogState latestLogState(String tenantId, Long projectId, String domain) {
        if ("RUN_LOG".equals(domain)) {
            RunRecordEntity entity = runRecordMapper.selectOne(new LambdaQueryWrapper<RunRecordEntity>()
                    .eq(RunRecordEntity::getTenantId, tenantId).eq(RunRecordEntity::getProjectId, projectId)
                    .in(RunRecordEntity::getLogStatus, "UPLOAD_FAILED", "AVAILABLE")
                    .orderByDesc(RunRecordEntity::getUpdatedAt)
                    .orderByDesc(RunRecordEntity::getId).last("limit 1"));
            return entity == null ? null : new LogState(String.valueOf(entity.getId()), entity.getLogStatus(),
                    entity.getLogErrorSummary(), entity.getUpdatedAt());
        }
        if ("DATA_SERVICE_LOG".equals(domain)) {
            DataServiceAccessLogEntity entity = dataServiceAccessLogMapper.selectOne(new LambdaQueryWrapper<DataServiceAccessLogEntity>()
                    .eq(DataServiceAccessLogEntity::getTenantId, tenantId).eq(DataServiceAccessLogEntity::getProjectId, projectId)
                    .in(DataServiceAccessLogEntity::getLogArchiveStatus, "FAILED", "AVAILABLE")
                    .orderByDesc(DataServiceAccessLogEntity::getOccurredAt)
                    .orderByDesc(DataServiceAccessLogEntity::getId).last("limit 1"));
            return entity == null ? null : new LogState(String.valueOf(entity.getId()), entity.getLogArchiveStatus(),
                    entity.getLogArchiveError(), entity.getOccurredAt());
        }
        if ("DATA_INGESTION_LOG".equals(domain)) {
            DataIngestionAccessLogEntity entity = dataIngestionAccessLogMapper.selectOne(new LambdaQueryWrapper<DataIngestionAccessLogEntity>()
                    .eq(DataIngestionAccessLogEntity::getTenantId, tenantId).eq(DataIngestionAccessLogEntity::getProjectId, projectId)
                    .in(DataIngestionAccessLogEntity::getLogArchiveStatus, "FAILED", "AVAILABLE")
                    .orderByDesc(DataIngestionAccessLogEntity::getOccurredAt)
                    .orderByDesc(DataIngestionAccessLogEntity::getId).last("limit 1"));
            return entity == null ? null : new LogState(String.valueOf(entity.getId()), entity.getLogArchiveStatus(),
                    entity.getLogArchiveError(), entity.getOccurredAt());
        }
        ProtocolConversionAccessLogEntity entity = protocolConversionAccessLogMapper.selectOne(new LambdaQueryWrapper<ProtocolConversionAccessLogEntity>()
                .eq(ProtocolConversionAccessLogEntity::getTenantId, tenantId).eq(ProtocolConversionAccessLogEntity::getProjectId, projectId)
                .in(ProtocolConversionAccessLogEntity::getLogArchiveStatus, "FAILED", "AVAILABLE")
                .orderByDesc(ProtocolConversionAccessLogEntity::getOccurredAt)
                .orderByDesc(ProtocolConversionAccessLogEntity::getId).last("limit 1"));
        return entity == null ? null : new LogState(String.valueOf(entity.getId()), entity.getLogArchiveStatus(),
                entity.getLogArchiveError(), entity.getOccurredAt());
    }

    private boolean isSupersededExecutionSignal(AlertSignal signal) {
        if (signal.getOccurredAt() == null || signal.getSubjectId() == null) {
            return false;
        }
        if (AlertSubjectType.WORKFLOW.name().equals(signal.getSubjectType())) {
            Optional<ObservationPosition> latest = latestCompletedWorkflow(signal);
            Optional<Long> currentWorkflowRunId = longValue(
                    signal.getEvidence() == null ? null : signal.getEvidence().get("targetRunId"));
            if (latest.isEmpty() || currentWorkflowRunId.isEmpty()) {
                return false;
            }
            ObservationPosition latestPosition = latest.get();
            Long currentId = currentWorkflowRunId.get();
            return !currentId.equals(latestPosition.id)
                    && isLater(latestPosition.observedAt, latestPosition.id, signal.getOccurredAt(), currentId);
        }
        LambdaQueryWrapper<RunRecordEntity> query = new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getTenantId, signal.getTenantId())
                .eq(RunRecordEntity::getProjectId, signal.getProjectId())
                .in(RunRecordEntity::getStatus, "SUCCESS", "FAILED", "ERROR");
        if (AlertSubjectType.COLLECTION_TASK.name().equals(signal.getSubjectType())) {
            query.eq(RunRecordEntity::getCollectionTaskId, signal.getSubjectId());
        } else if (AlertSubjectType.QUALITY_TASK.name().equals(signal.getSubjectType())) {
            query.eq(RunRecordEntity::getQualityTaskId, signal.getSubjectId());
        } else {
            return false;
        }
        RunRecordEntity latest = runRecordMapper.selectOne(query.orderByDesc(RunRecordEntity::getEndedAt)
                .orderByDesc(RunRecordEntity::getId).last("limit 1"));
        Optional<Long> currentId = longValue(signal.getSourceId());
        return latest != null && currentId.isPresent() && !currentId.get().equals(latest.getId())
                && isLater(latest.getEndedAt(), latest.getId(), signal.getOccurredAt(), currentId.get());
    }

    private Optional<ObservationPosition> latestCompletedWorkflow(AlertSignal signal) {
        List<WorkflowRunOutcome> outcomes = runRecordMapper.selectRecentWorkflowRunOutcomes(
                signal.getTenantId(), signal.getProjectId(), signal.getSubjectId(), 1);
        if (outcomes == null || outcomes.isEmpty()) {
            return Optional.empty();
        }
        WorkflowRunOutcome latest = outcomes.get(0);
        return Optional.of(new ObservationPosition(latest.getWorkflowRunId(), latest.getObservedAt()));
    }

    private boolean isSupersededInvocationSignal(AlertSignal signal) {
        if (signal.getOccurredAt() == null || signal.getSubjectId() == null) {
            return false;
        }
        Optional<Long> currentId = longValue(signal.getSourceId());
        if (currentId.isEmpty()) {
            return false;
        }
        Long sourceId = currentId.get();
        if (AlertSubjectType.DATA_SERVICE.name().equals(signal.getSubjectType())) {
            DataServiceAccessLogEntity latest = dataServiceAccessLogMapper.selectOne(new LambdaQueryWrapper<DataServiceAccessLogEntity>()
                    .eq(DataServiceAccessLogEntity::getTenantId, signal.getTenantId())
                    .eq(DataServiceAccessLogEntity::getProjectId, signal.getProjectId())
                    .eq(DataServiceAccessLogEntity::getServiceId, signal.getSubjectId())
                    .orderByDesc(DataServiceAccessLogEntity::getOccurredAt)
                    .orderByDesc(DataServiceAccessLogEntity::getId).last("limit 1"));
            return latest != null && !sourceId.equals(latest.getId())
                    && isLater(latest.getOccurredAt(), latest.getId(), signal.getOccurredAt(), sourceId);
        }
        if (AlertSubjectType.DATA_INGESTION_SERVICE.name().equals(signal.getSubjectType())) {
            DataIngestionAccessLogEntity latest = dataIngestionAccessLogMapper.selectOne(new LambdaQueryWrapper<DataIngestionAccessLogEntity>()
                    .eq(DataIngestionAccessLogEntity::getTenantId, signal.getTenantId())
                    .eq(DataIngestionAccessLogEntity::getProjectId, signal.getProjectId())
                    .eq(DataIngestionAccessLogEntity::getServiceId, signal.getSubjectId())
                    .orderByDesc(DataIngestionAccessLogEntity::getOccurredAt)
                    .orderByDesc(DataIngestionAccessLogEntity::getId).last("limit 1"));
            return latest != null && !sourceId.equals(latest.getId())
                    && isLater(latest.getOccurredAt(), latest.getId(), signal.getOccurredAt(), sourceId);
        }
        if (AlertSubjectType.PROTOCOL_CONVERSION_SERVICE.name().equals(signal.getSubjectType())) {
            ProtocolConversionAccessLogEntity latest = protocolConversionAccessLogMapper.selectOne(new LambdaQueryWrapper<ProtocolConversionAccessLogEntity>()
                    .eq(ProtocolConversionAccessLogEntity::getTenantId, signal.getTenantId())
                    .eq(ProtocolConversionAccessLogEntity::getProjectId, signal.getProjectId())
                    .eq(ProtocolConversionAccessLogEntity::getServiceId, signal.getSubjectId())
                    .orderByDesc(ProtocolConversionAccessLogEntity::getOccurredAt)
                    .orderByDesc(ProtocolConversionAccessLogEntity::getId).last("limit 1"));
            return latest != null && !sourceId.equals(latest.getId())
                    && isLater(latest.getOccurredAt(), latest.getId(), signal.getOccurredAt(), sourceId);
        }
        return false;
    }

    private boolean isSupersededLogSignal(AlertSignal signal, LogState latest) {
        if (latest == null || signal.getOccurredAt() == null) {
            return false;
        }
        boolean sameState = String.valueOf(latest.sourceId).equals(signal.getSourceId())
                && String.valueOf(latest.status).equalsIgnoreCase(signal.getStatus());
        Optional<Long> latestId = longValue(latest.sourceId);
        Optional<Long> signalId = longValue(signal.getSourceId());
        return !sameState && latestId.isPresent() && signalId.isPresent()
                && isLater(latest.observedAt, latestId.get(), signal.getOccurredAt(), signalId.get());
    }

    private boolean isLater(LocalDateTime candidateAt, Long candidateId,
                            LocalDateTime currentAt, Long currentId) {
        if (candidateAt == null || currentAt == null) {
            return false;
        }
        if (candidateAt.isAfter(currentAt)) {
            return true;
        }
        return candidateAt.isEqual(currentAt) && candidateId != null && currentId != null
                && candidateId.longValue() > currentId.longValue();
    }

    private Optional<Long> longValue(Object value) {
        if (value instanceof Number) {
            return Optional.of(Long.valueOf(((Number) value).longValue()));
        }
        try {
            return value == null ? Optional.empty() : Optional.of(Long.valueOf(String.valueOf(value)));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private int consecutiveFailures(AlertSignal signal) {
        List<RunRecordEntity> runs;
        if (AlertSubjectType.COLLECTION_TASK.name().equals(signal.getSubjectType())) {
            runs = runRecordMapper.selectList(new LambdaQueryWrapper<RunRecordEntity>()
                    .eq(RunRecordEntity::getTenantId, signal.getTenantId()).eq(RunRecordEntity::getProjectId, signal.getProjectId())
                    .eq(RunRecordEntity::getCollectionTaskId, signal.getSubjectId())
                    .in(RunRecordEntity::getStatus, "SUCCESS", "FAILED", "ERROR")
                    .orderByDesc(RunRecordEntity::getEndedAt)
                    .orderByDesc(RunRecordEntity::getId).last("limit 20"));
            return leadingFailures(runs);
        }
        if (AlertSubjectType.QUALITY_TASK.name().equals(signal.getSubjectType())) {
            runs = runRecordMapper.selectList(new LambdaQueryWrapper<RunRecordEntity>()
                    .eq(RunRecordEntity::getTenantId, signal.getTenantId()).eq(RunRecordEntity::getProjectId, signal.getProjectId())
                    .eq(RunRecordEntity::getQualityTaskId, signal.getSubjectId())
                    .in(RunRecordEntity::getStatus, "SUCCESS", "FAILED", "ERROR")
                    .orderByDesc(RunRecordEntity::getEndedAt)
                    .orderByDesc(RunRecordEntity::getId).last("limit 20"));
            return leadingFailures(runs);
        }
        List<WorkflowRunOutcome> workflowOutcomes = runRecordMapper.selectRecentWorkflowRunOutcomes(
                signal.getTenantId(), signal.getProjectId(), signal.getSubjectId(), 20);
        int count = 0;
        if (workflowOutcomes == null) {
            return count;
        }
        for (WorkflowRunOutcome outcome : workflowOutcomes) {
            if (!Integer.valueOf(1).equals(outcome.getFailed())) {
                break;
            }
            count++;
        }
        return count;
    }

    private int leadingFailures(List<RunRecordEntity> runs) {
        int count = 0;
        for (RunRecordEntity run : runs) {
            if ("FAILED".equalsIgnoreCase(run.getStatus()) || "ERROR".equalsIgnoreCase(run.getStatus())) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private AlertObservation fromSignal(AlertSignal signal, boolean active) {
        return new AlertObservation()
                .setActive(active)
                .setSubjectType(signal.getSubjectType())
                .setSubjectId(signal.getSubjectId())
                .setSubjectKey(StringUtils.hasText(signal.getSubjectKey()) ? signal.getSubjectKey() : String.valueOf(signal.getSubjectId()))
                .setSubjectName(signal.getSubjectName())
                .setOwnerUserId(signal.getOwnerUserId())
                .setTargetPath(signal.getTargetPath())
                .setSourceType(signal.getSignalType())
                .setSourceId(signal.getSourceId())
                .setSourceEventKey(signal.getSourceEventKey())
                .setObservedAt(signal.getOccurredAt())
                .setEvidence(copy(signal.getEvidence()));
    }

    private String failureSummary(AlertRuleType type, Map<String, Object> evidence) {
        if (type == AlertRuleType.CONSECUTIVE_FAILURES) {
            return "已连续失败 " + evidence.get("consecutiveFailures") + " 次";
        }
        return "执行失败";
    }

    private boolean isPeriodic(AlertRuleType type) {
        return Arrays.asList(AlertRuleType.RUN_TIMEOUT, AlertRuleType.SERVICE_FAILURE_RATE,
                AlertRuleType.WORKER_OFFLINE, AlertRuleType.QUEUE_BACKLOG,
                AlertRuleType.SCHEDULE_DELAY, AlertRuleType.LOG_UPLOAD_FAILED).contains(type);
    }

    private boolean enabled() {
        return properties.getAlert() != null && properties.getAlert().isEnabled();
    }

    private int intCondition(AlertRuleEntity rule, String name, int defaultValue) {
        Object value = rule.getConditionJson() == null ? null : rule.getConditionJson().get(name);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private List<String> stringListCondition(AlertRuleEntity rule, String name) {
        Object value = rule.getConditionJson() == null ? null : rule.getConditionJson().get(name);
        if (!(value instanceof Iterable<?>)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (Object item : (Iterable<?>) value) {
            if (item != null) {
                result.add(String.valueOf(item).toUpperCase());
            }
        }
        return result;
    }

    private Map<String, Object> copy(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(value);
    }

    private String logDomainName(String domain) {
        if ("RUN_LOG".equals(domain)) {
            return "任务运行日志";
        }
        if ("DATA_SERVICE_LOG".equals(domain)) {
            return "数据服务调用日志";
        }
        if ("DATA_INGESTION_LOG".equals(domain)) {
            return "数据接入调用日志";
        }
        return "协议转换调用日志";
    }

    private static final class LogState {
        private final String sourceId;
        private final String status;
        private final String error;
        private final LocalDateTime observedAt;
        private final boolean failed;
        private final boolean available;

        private LogState(String sourceId, String status, String error, LocalDateTime observedAt) {
            this.sourceId = sourceId;
            this.status = status;
            this.error = error;
            this.observedAt = observedAt;
            this.failed = "FAILED".equalsIgnoreCase(status) || "UPLOAD_FAILED".equalsIgnoreCase(status);
            this.available = "AVAILABLE".equalsIgnoreCase(status);
        }
    }

    private static final class ObservationPosition {
        private final Long id;
        private final LocalDateTime observedAt;

        private ObservationPosition(Long id, LocalDateTime observedAt) {
            this.id = id;
            this.observedAt = observedAt;
        }
    }
}
