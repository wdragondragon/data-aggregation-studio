package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.AlertSubjectType;
import com.jdragon.studio.infra.entity.AlertRuleEntity;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.DataIngestionAccessCounterEntity;
import com.jdragon.studio.infra.entity.DataIngestionServiceEntity;
import com.jdragon.studio.infra.entity.DataServiceAccessCounterEntity;
import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.ProjectWorkerBindingEntity;
import com.jdragon.studio.infra.entity.ProtocolConversionAccessCounterEntity;
import com.jdragon.studio.infra.entity.ProtocolConversionServiceEntity;
import com.jdragon.studio.infra.entity.QualityTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataIngestionAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.ProjectWorkerBindingMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionAccessCounterMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.QualityTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.model.AlertObservation;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class AlertPeriodicObservationProvider {

    private final RunRecordMapper runRecordMapper;
    private final DispatchTaskMapper dispatchTaskMapper;
    private final WorkerLeaseMapper workerLeaseMapper;
    private final ProjectWorkerBindingMapper projectWorkerBindingMapper;
    private final CollectionTaskDefinitionMapper collectionTaskDefinitionMapper;
    private final QualityTaskDefinitionMapper qualityTaskDefinitionMapper;
    private final WorkflowDefinitionMapper workflowDefinitionMapper;
    private final DataServiceDefinitionMapper dataServiceDefinitionMapper;
    private final DataIngestionServiceMapper dataIngestionServiceMapper;
    private final ProtocolConversionServiceMapper protocolConversionServiceMapper;
    private final DataServiceAccessCounterMapper dataServiceAccessCounterMapper;
    private final DataIngestionAccessCounterMapper dataIngestionAccessCounterMapper;
    private final ProtocolConversionAccessCounterMapper protocolConversionAccessCounterMapper;

    AlertPeriodicObservationProvider(RunRecordMapper runRecordMapper,
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
                                     ProtocolConversionAccessCounterMapper protocolConversionAccessCounterMapper) {
        this.runRecordMapper = runRecordMapper;
        this.dispatchTaskMapper = dispatchTaskMapper;
        this.workerLeaseMapper = workerLeaseMapper;
        this.projectWorkerBindingMapper = projectWorkerBindingMapper;
        this.collectionTaskDefinitionMapper = collectionTaskDefinitionMapper;
        this.qualityTaskDefinitionMapper = qualityTaskDefinitionMapper;
        this.workflowDefinitionMapper = workflowDefinitionMapper;
        this.dataServiceDefinitionMapper = dataServiceDefinitionMapper;
        this.dataIngestionServiceMapper = dataIngestionServiceMapper;
        this.protocolConversionServiceMapper = protocolConversionServiceMapper;
        this.dataServiceAccessCounterMapper = dataServiceAccessCounterMapper;
        this.dataIngestionAccessCounterMapper = dataIngestionAccessCounterMapper;
        this.protocolConversionAccessCounterMapper = protocolConversionAccessCounterMapper;
    }

    List<AlertObservation> timeoutObservations(AlertRuleEntity rule) {
        int minutes = intCondition(rule, "durationMinutes", 30);
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(minutes);
        LambdaQueryWrapper<RunRecordEntity> query = new LambdaQueryWrapper<RunRecordEntity>()
                .eq(RunRecordEntity::getTenantId, rule.getTenantId())
                .eq(RunRecordEntity::getProjectId, rule.getProjectId())
                .eq(RunRecordEntity::getStatus, "RUNNING")
                .le(RunRecordEntity::getStartedAt, cutoff);
        applyRunSubjectFilter(query, rule);
        List<RunRecordEntity> runs = runRecordMapper.selectList(query.orderByAsc(RunRecordEntity::getStartedAt));
        Map<String, AlertObservation> bySubject = new LinkedHashMap<String, AlertObservation>();
        for (RunRecordEntity run : runs) {
            Optional<Resource> candidate = resourceForRun(rule.getSubjectType(), run);
            if (candidate.isEmpty() || !matchesRuleSubject(rule, candidate.get())) {
                continue;
            }
            Resource resource = candidate.get();
            long durationMinutes = Math.max(0L, Duration.between(run.getStartedAt(), LocalDateTime.now()).toMinutes());
            Map<String, Object> evidence = new LinkedHashMap<String, Object>();
            evidence.put("runRecordId", run.getId());
            evidence.put("startedAt", run.getStartedAt());
            evidence.put("durationMinutes", durationMinutes);
            evidence.put("thresholdMinutes", minutes);
            evidence.put("targetClusterId", run.getRequestedClusterId());
            evidence.put("actualClusterId", run.getActualClusterId());
            evidence.put("actualClusterCode", run.getActualClusterCode());
            bySubject.putIfAbsent(resource.key(), observation(rule, resource, true,
                    rule.getName() + "：" + resource.name + " 已运行 " + durationMinutes + " 分钟",
                    evidence, String.valueOf(run.getId()),
                    AlertIncidentService.targetPath(rule.getSubjectType(), resource.id, runSourceId(rule, run))));
        }
        return new ArrayList<AlertObservation>(bySubject.values());
    }

    List<AlertObservation> failureRateObservations(AlertRuleEntity rule) {
        int windowHours = intCondition(rule, "windowHours", 1);
        int threshold = intCondition(rule, "failureRatePercent", 20);
        int minimumRequests = intCondition(rule, "minimumRequests", 20);
        LocalDateTime bucketStart = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0)
                .minusHours(windowHours - 1L);
        List<AlertObservation> observations = new ArrayList<AlertObservation>();
        for (Resource resource : serviceResources(rule)) {
            long total = 0L;
            long failed = 0L;
            if (AlertSubjectType.DATA_SERVICE.name().equals(rule.getSubjectType())) {
                List<DataServiceAccessCounterEntity> counters = dataServiceAccessCounterMapper.selectList(
                        new LambdaQueryWrapper<DataServiceAccessCounterEntity>()
                                .eq(DataServiceAccessCounterEntity::getTenantId, rule.getTenantId())
                                .eq(DataServiceAccessCounterEntity::getProjectId, rule.getProjectId())
                                .eq(DataServiceAccessCounterEntity::getServiceId, resource.id)
                                .ge(DataServiceAccessCounterEntity::getBucketStart, bucketStart));
                for (DataServiceAccessCounterEntity counter : counters) {
                    long count = safeLong(counter.getAccessCount());
                    total += count;
                    if (!Integer.valueOf(1).equals(counter.getSuccess())) {
                        failed += count;
                    }
                }
            } else if (AlertSubjectType.DATA_INGESTION_SERVICE.name().equals(rule.getSubjectType())) {
                List<DataIngestionAccessCounterEntity> counters = dataIngestionAccessCounterMapper.selectList(
                        new LambdaQueryWrapper<DataIngestionAccessCounterEntity>()
                                .eq(DataIngestionAccessCounterEntity::getTenantId, rule.getTenantId())
                                .eq(DataIngestionAccessCounterEntity::getProjectId, rule.getProjectId())
                                .eq(DataIngestionAccessCounterEntity::getServiceId, resource.id)
                                .ge(DataIngestionAccessCounterEntity::getBucketStart, bucketStart));
                for (DataIngestionAccessCounterEntity counter : counters) {
                    long count = safeLong(counter.getAccessCount());
                    total += count;
                    if (!Integer.valueOf(1).equals(counter.getSuccess())) {
                        failed += count;
                    }
                }
            } else {
                List<ProtocolConversionAccessCounterEntity> counters = protocolConversionAccessCounterMapper.selectList(
                        new LambdaQueryWrapper<ProtocolConversionAccessCounterEntity>()
                                .eq(ProtocolConversionAccessCounterEntity::getTenantId, rule.getTenantId())
                                .eq(ProtocolConversionAccessCounterEntity::getProjectId, rule.getProjectId())
                                .eq(ProtocolConversionAccessCounterEntity::getServiceId, resource.id)
                                .ge(ProtocolConversionAccessCounterEntity::getBucketStart, bucketStart));
                for (ProtocolConversionAccessCounterEntity counter : counters) {
                    long count = safeLong(counter.getAccessCount());
                    total += count;
                    if (!Integer.valueOf(1).equals(counter.getSuccess())) {
                        failed += count;
                    }
                }
            }
            double rate = total == 0L ? 0D : (failed * 100D / total);
            boolean active = total >= minimumRequests && rate >= threshold;
            Map<String, Object> evidence = new LinkedHashMap<String, Object>();
            evidence.put("windowHours", windowHours);
            evidence.put("requestCount", total);
            evidence.put("failureCount", failed);
            evidence.put("failureRatePercent", Math.round(rate * 100D) / 100D);
            evidence.put("thresholdPercent", threshold);
            evidence.put("minimumRequests", minimumRequests);
            putResourceClusterEvidence(evidence, resource);
            observations.add(observation(rule, resource, active,
                    active ? rule.getName() + "：" + resource.name + " 失败率 "
                            + evidence.get("failureRatePercent") + "%"
                            : rule.getName() + "：" + resource.name + " 失败率已恢复",
                    evidence, null, AlertIncidentService.targetPath(rule.getSubjectType(), resource.id, null)));
        }
        return observations;
    }

    List<AlertObservation> workerObservations(AlertRuleEntity rule) {
        int offlineSeconds = intCondition(rule, "offlineSeconds", 120);
        LocalDateTime now = LocalDateTime.now();
        List<AlertObservation> result = new ArrayList<AlertObservation>();
        for (Resource group : workerGroups(rule)) {
            List<WorkerLeaseEntity> leases = workerLeaseMapper.selectList(new LambdaQueryWrapper<WorkerLeaseEntity>()
                    .eq(WorkerLeaseEntity::getTenantId, rule.getTenantId())
                    .eq(WorkerLeaseEntity::getWorkerGroupCode, group.code));
            boolean online = false;
            LocalDateTime latestHeartbeat = null;
            int reportedOnlineLeaseCount = 0;
            int validLeaseCount = 0;
            Set<Long> actualClusterIds = new LinkedHashSet<Long>();
            Set<String> actualClusterCodes = new LinkedHashSet<String>();
            for (WorkerLeaseEntity lease : leases) {
                if (lease.getRuntimeClusterId() != null) {
                    actualClusterIds.add(lease.getRuntimeClusterId());
                }
                if (StringUtils.hasText(lease.getRuntimeClusterCode())) {
                    actualClusterCodes.add(lease.getRuntimeClusterCode().trim());
                }
                if (!StudioConstants.WORKER_STATUS_ONLINE.equalsIgnoreCase(lease.getStatus())) {
                    continue;
                }
                reportedOnlineLeaseCount++;
                if (lease.getLastHeartbeatAt() != null
                        && (latestHeartbeat == null || lease.getLastHeartbeatAt().isAfter(latestHeartbeat))) {
                    latestHeartbeat = lease.getLastHeartbeatAt();
                }
                boolean valid = (lease.getLeaseExpiresAt() != null && lease.getLeaseExpiresAt().isAfter(now))
                        || (lease.getLastHeartbeatAt() != null
                        && lease.getLastHeartbeatAt().isAfter(now.minusSeconds(offlineSeconds)));
                if (valid) {
                    online = true;
                    validLeaseCount++;
                }
            }
            boolean active = !online
                    && (latestHeartbeat == null || !latestHeartbeat.isAfter(now.minusSeconds(offlineSeconds)));
            Map<String, Object> evidence = new LinkedHashMap<String, Object>();
            evidence.put("workerGroupCode", group.code);
            evidence.put("offlineSeconds", offlineSeconds);
            evidence.put("latestHeartbeatAt", latestHeartbeat);
            evidence.put("instanceCount", validLeaseCount);
            evidence.put("reportedOnlineLeaseCount", reportedOnlineLeaseCount);
            evidence.put("actualClusterIds", new ArrayList<Long>(actualClusterIds));
            evidence.put("actualClusterCodes", new ArrayList<String>(actualClusterCodes));
            if (actualClusterIds.size() == 1) {
                evidence.put("actualClusterId", actualClusterIds.iterator().next());
            }
            result.add(observation(rule, group, active,
                    active ? "Worker 组 " + group.name + " 已离线" : "Worker 组 " + group.name + " 已恢复在线",
                    evidence, null,
                    AlertIncidentService.targetPath(AlertSubjectType.WORKER_GROUP.name(), group.id, null)));
        }
        return result;
    }

    List<AlertObservation> queueObservations(AlertRuleEntity rule) {
        int queuedThreshold = intCondition(rule, "queuedCount", 20);
        int waitMinutes = intCondition(rule, "oldestWaitMinutes", 5);
        LocalDateTime now = LocalDateTime.now();
        List<DispatchTaskEntity> queued = new ArrayList<DispatchTaskEntity>(dispatchTaskMapper.selectList(
                new LambdaQueryWrapper<DispatchTaskEntity>()
                        .eq(DispatchTaskEntity::getTenantId, rule.getTenantId())
                        .eq(DispatchTaskEntity::getProjectId, rule.getProjectId())
                        .eq(DispatchTaskEntity::getStatus, "QUEUED")
                        .and(wrapper -> wrapper.isNull(DispatchTaskEntity::getScheduledFireTime)
                                .or().le(DispatchTaskEntity::getScheduledFireTime, now))
                        .orderByAsc(DispatchTaskEntity::getCreatedAt)));
        queued.sort((left, right) -> compareOptional(queueWaitStartedAt(left), queueWaitStartedAt(right)));
        if (AlertSubjectType.PROJECT_QUEUE.name().equals(rule.getSubjectType())) {
            return Collections.singletonList(queueObservation(rule,
                    new Resource(null, "PROJECT_QUEUE", "PROJECT_QUEUE", "项目调度队列", null), queued,
                    queuedThreshold, waitMinutes, now));
        }
        Map<String, List<DispatchTaskEntity>> byGroup = new LinkedHashMap<String, List<DispatchTaskEntity>>();
        for (DispatchTaskEntity task : queued) {
            String group = StringUtils.hasText(task.getWorkerGroupCode()) ? task.getWorkerGroupCode() : "UNASSIGNED";
            byGroup.computeIfAbsent(group, key -> new ArrayList<DispatchTaskEntity>()).add(task);
        }
        List<AlertObservation> result = new ArrayList<AlertObservation>();
        for (Resource group : workerGroups(rule)) {
            result.add(queueObservation(rule, group,
                    byGroup.getOrDefault(group.code, Collections.<DispatchTaskEntity>emptyList()),
                    queuedThreshold, waitMinutes, now));
        }
        return result;
    }

    List<AlertObservation> scheduleDelayObservations(AlertRuleEntity rule) {
        int delayMinutes = intCondition(rule, "delayMinutes", 10);
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(delayMinutes);
        LambdaQueryWrapper<DispatchTaskEntity> query = new LambdaQueryWrapper<DispatchTaskEntity>()
                .eq(DispatchTaskEntity::getTenantId, rule.getTenantId())
                .eq(DispatchTaskEntity::getProjectId, rule.getProjectId())
                .eq(DispatchTaskEntity::getStatus, "QUEUED")
                .isNotNull(DispatchTaskEntity::getScheduledFireTime)
                .le(DispatchTaskEntity::getScheduledFireTime, cutoff);
        applyDispatchSubjectFilter(query, rule);
        List<DispatchTaskEntity> tasks = dispatchTaskMapper.selectList(
                query.orderByAsc(DispatchTaskEntity::getScheduledFireTime));
        Map<String, AlertObservation> result = new LinkedHashMap<String, AlertObservation>();
        for (DispatchTaskEntity task : tasks) {
            Optional<Resource> candidate = resourceForDispatch(rule.getSubjectType(), task);
            if (candidate.isEmpty() || !matchesRuleSubject(rule, candidate.get())) {
                continue;
            }
            Resource resource = candidate.get();
            long delay = Duration.between(task.getScheduledFireTime(), LocalDateTime.now()).toMinutes();
            Map<String, Object> evidence = new LinkedHashMap<String, Object>();
            evidence.put("dispatchTaskId", task.getId());
            evidence.put("scheduledFireTime", task.getScheduledFireTime());
            evidence.put("delayMinutes", delay);
            evidence.put("thresholdMinutes", delayMinutes);
            evidence.put("targetClusterId", task.getTargetClusterId());
            result.putIfAbsent(resource.key(), observation(rule, resource, true,
                    resource.name + " 调度延迟 " + delay + " 分钟", evidence, String.valueOf(task.getId()),
                    AlertIncidentService.targetPath(rule.getSubjectType(), resource.id,
                            AlertSubjectType.WORKFLOW.name().equals(rule.getSubjectType())
                                    ? task.getWorkflowRunId() : task.getRunRecordId())));
        }
        return new ArrayList<AlertObservation>(result.values());
    }

    private AlertObservation queueObservation(AlertRuleEntity rule, Resource subject, List<DispatchTaskEntity> tasks,
                                              int threshold, int waitMinutes, LocalDateTime now) {
        DispatchTaskEntity oldestTask = tasks.isEmpty() ? null : tasks.get(0);
        LocalDateTime oldestCreatedAt = oldestTask == null ? null : oldestTask.getCreatedAt();
        Optional<LocalDateTime> oldestWaitStartedAt = queueWaitStartedAt(oldestTask);
        long oldestWait = oldestWaitStartedAt
                .map(startedAt -> Math.max(0L, Duration.between(startedAt, now).toMinutes()))
                .orElse(0L);
        boolean active = tasks.size() >= threshold && oldestWait >= waitMinutes;
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("queuedCount", tasks.size());
        evidence.put("queuedThreshold", threshold);
        evidence.put("oldestCreatedAt", oldestCreatedAt);
        evidence.put("oldestWaitStartedAt", oldestWaitStartedAt.orElse(null));
        evidence.put("oldestWaitMinutes", oldestWait);
        evidence.put("waitThresholdMinutes", waitMinutes);
        evidence.put("targetClusterId", oldestTask == null ? null : oldestTask.getTargetClusterId());
        Set<Long> targetClusterIds = new LinkedHashSet<Long>();
        for (DispatchTaskEntity task : tasks) {
            if (task.getTargetClusterId() != null) {
                targetClusterIds.add(task.getTargetClusterId());
            }
        }
        evidence.put("targetClusterIds", new ArrayList<Long>(targetClusterIds));
        return observation(rule, subject, active,
                active ? subject.name + "积压 " + tasks.size() + " 个任务，最老等待 " + oldestWait + " 分钟"
                        : subject.name + "积压已恢复",
                evidence, tasks.isEmpty() ? null : String.valueOf(tasks.get(0).getId()),
                AlertIncidentService.targetPath(rule.getSubjectType(), subject.id, null));
    }

    private Optional<LocalDateTime> queueWaitStartedAt(DispatchTaskEntity task) {
        if (task == null) {
            return Optional.empty();
        }
        LocalDateTime createdAt = task.getCreatedAt();
        LocalDateTime scheduledFireTime = task.getScheduledFireTime();
        if (createdAt == null) {
            return Optional.ofNullable(scheduledFireTime);
        }
        if (scheduledFireTime == null) {
            return Optional.of(createdAt);
        }
        return Optional.of(createdAt.isAfter(scheduledFireTime) ? createdAt : scheduledFireTime);
    }

    private int compareOptional(Optional<LocalDateTime> left, Optional<LocalDateTime> right) {
        if (left.isEmpty()) {
            return right.isEmpty() ? 0 : 1;
        }
        return right.isEmpty() ? -1 : left.get().compareTo(right.get());
    }

    private AlertObservation observation(AlertRuleEntity rule, Resource resource, boolean active, String summary,
                                         Map<String, Object> evidence, String sourceId, String targetPath) {
        return new AlertObservation()
                .setActive(active)
                .setSubjectType(rule.getSubjectType())
                .setSubjectKey(resource.key())
                .setSubjectId(resource.id)
                .setSubjectName(resource.name)
                .setOwnerUserId(resource.ownerUserId)
                .setTargetPath(targetPath)
                .setSummary(summary)
                .setEvidence(evidence)
                .setSourceType("SCHEDULED_EVALUATION")
                .setSourceId(sourceId)
                .setObservedAt(LocalDateTime.now());
    }

    private List<Resource> serviceResources(AlertRuleEntity rule) {
        List<Resource> result = new ArrayList<Resource>();
        if (AlertSubjectType.DATA_SERVICE.name().equals(rule.getSubjectType())) {
            for (DataServiceDefinitionEntity entity : dataServiceDefinitionMapper.selectList(
                    new LambdaQueryWrapper<DataServiceDefinitionEntity>()
                            .eq(DataServiceDefinitionEntity::getTenantId, rule.getTenantId())
                            .eq(DataServiceDefinitionEntity::getProjectId, rule.getProjectId())
                            .eq(rule.getSubjectId() != null, DataServiceDefinitionEntity::getId, rule.getSubjectId()))) {
                result.add(new Resource(entity.getId(), String.valueOf(entity.getId()), null,
                        entity.getServiceName(), entity.getCreatedBy(), entity.getRuntimeClusterId()));
            }
        } else if (AlertSubjectType.DATA_INGESTION_SERVICE.name().equals(rule.getSubjectType())) {
            for (DataIngestionServiceEntity entity : dataIngestionServiceMapper.selectList(
                    new LambdaQueryWrapper<DataIngestionServiceEntity>()
                            .eq(DataIngestionServiceEntity::getTenantId, rule.getTenantId())
                            .eq(DataIngestionServiceEntity::getProjectId, rule.getProjectId())
                            .eq(rule.getSubjectId() != null, DataIngestionServiceEntity::getId, rule.getSubjectId()))) {
                result.add(new Resource(entity.getId(), String.valueOf(entity.getId()), null,
                        entity.getServiceName(), entity.getCreatedBy(), entity.getRuntimeClusterId()));
            }
        } else {
            for (ProtocolConversionServiceEntity entity : protocolConversionServiceMapper.selectList(
                    new LambdaQueryWrapper<ProtocolConversionServiceEntity>()
                            .eq(ProtocolConversionServiceEntity::getTenantId, rule.getTenantId())
                            .eq(ProtocolConversionServiceEntity::getProjectId, rule.getProjectId())
                            .eq(rule.getSubjectId() != null, ProtocolConversionServiceEntity::getId, rule.getSubjectId()))) {
                result.add(new Resource(entity.getId(), String.valueOf(entity.getId()), null,
                        entity.getServiceName(), entity.getCreatedBy(), entity.getRuntimeClusterId()));
            }
        }
        return result;
    }

    private List<Resource> workerGroups(AlertRuleEntity rule) {
        LambdaQueryWrapper<ProjectWorkerBindingEntity> query = new LambdaQueryWrapper<ProjectWorkerBindingEntity>()
                .eq(ProjectWorkerBindingEntity::getTenantId, rule.getTenantId())
                .eq(ProjectWorkerBindingEntity::getProjectId, rule.getProjectId())
                .eq(rule.getSubjectId() != null, ProjectWorkerBindingEntity::getId, rule.getSubjectId())
                .eq(rule.getSubjectId() == null, ProjectWorkerBindingEntity::getEnabled, 1);
        List<ProjectWorkerBindingEntity> bindings = projectWorkerBindingMapper.selectList(query);
        if (rule.getSubjectId() != null && (bindings.isEmpty()
                || !Integer.valueOf(1).equals(bindings.get(0).getEnabled()))) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "The configured Worker group binding is missing or disabled: " + rule.getSubjectId());
        }
        Map<String, Resource> result = new LinkedHashMap<String, Resource>();
        for (ProjectWorkerBindingEntity binding : bindings) {
            if (!Integer.valueOf(1).equals(binding.getEnabled())) {
                continue;
            }
            String code = binding.getWorkerGroupCode();
            if (!StringUtils.hasText(code)) {
                continue;
            }
            Long id = binding.getId();
            if (rule.getSubjectId() != null && !rule.getSubjectId().equals(id)) {
                continue;
            }
            result.putIfAbsent(code, new Resource(id, code, code, code, null));
        }
        return new ArrayList<Resource>(result.values());
    }

    private Optional<Resource> resourceForRun(String subjectType, RunRecordEntity run) {
        if (AlertSubjectType.COLLECTION_TASK.name().equals(subjectType)) {
            return collectionResource(run.getCollectionTaskId(), run.getTenantId(), run.getProjectId());
        }
        if (AlertSubjectType.QUALITY_TASK.name().equals(subjectType)) {
            return qualityResource(run.getQualityTaskId(), run.getTenantId(), run.getProjectId());
        }
        if (AlertSubjectType.WORKFLOW.name().equals(subjectType)) {
            return workflowResource(run.getWorkflowDefinitionId(), run.getTenantId(), run.getProjectId());
        }
        return Optional.empty();
    }

    private Optional<Resource> resourceForDispatch(String subjectType, DispatchTaskEntity task) {
        if (AlertSubjectType.COLLECTION_TASK.name().equals(subjectType)) {
            return collectionResource(task.getCollectionTaskId(), task.getTenantId(), task.getProjectId());
        }
        if (AlertSubjectType.QUALITY_TASK.name().equals(subjectType)) {
            return qualityResource(task.getQualityTaskId(), task.getTenantId(), task.getProjectId());
        }
        if (AlertSubjectType.WORKFLOW.name().equals(subjectType)) {
            return workflowResource(task.getWorkflowDefinitionId(), task.getTenantId(), task.getProjectId());
        }
        return Optional.empty();
    }

    private Optional<Resource> collectionResource(Long id, String tenantId, Long projectId) {
        if (id == null || tenantId == null || projectId == null) {
            return Optional.empty();
        }
        CollectionTaskDefinitionEntity entity = collectionTaskDefinitionMapper.selectOne(
                new LambdaQueryWrapper<CollectionTaskDefinitionEntity>()
                        .eq(CollectionTaskDefinitionEntity::getId, id)
                        .eq(CollectionTaskDefinitionEntity::getTenantId, tenantId)
                        .eq(CollectionTaskDefinitionEntity::getProjectId, projectId)
                        .last("limit 1"));
        return Optional.ofNullable(entity).map(value -> new Resource(value.getId(), String.valueOf(value.getId()),
                null, value.getName(), value.getCreatedBy(), value.getRuntimeClusterId()));
    }

    private Optional<Resource> qualityResource(Long id, String tenantId, Long projectId) {
        if (id == null || tenantId == null || projectId == null) {
            return Optional.empty();
        }
        QualityTaskDefinitionEntity entity = qualityTaskDefinitionMapper.selectOne(
                new LambdaQueryWrapper<QualityTaskDefinitionEntity>()
                        .eq(QualityTaskDefinitionEntity::getId, id)
                        .eq(QualityTaskDefinitionEntity::getTenantId, tenantId)
                        .eq(QualityTaskDefinitionEntity::getProjectId, projectId)
                        .last("limit 1"));
        return Optional.ofNullable(entity).map(value -> new Resource(value.getId(), String.valueOf(value.getId()),
                null, value.getTaskName(), value.getCreatedBy(), value.getRuntimeClusterId()));
    }

    private Optional<Resource> workflowResource(Long id, String tenantId, Long projectId) {
        if (id == null || tenantId == null || projectId == null) {
            return Optional.empty();
        }
        WorkflowDefinitionEntity entity = workflowDefinitionMapper.selectOne(
                new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                        .eq(WorkflowDefinitionEntity::getId, id)
                        .eq(WorkflowDefinitionEntity::getTenantId, tenantId)
                        .eq(WorkflowDefinitionEntity::getProjectId, projectId)
                        .last("limit 1"));
        return Optional.ofNullable(entity).map(value -> new Resource(value.getId(), String.valueOf(value.getId()),
                null, value.getName(), value.getCreatedBy(), value.getRuntimeClusterId()));
    }

    private boolean matchesRuleSubject(AlertRuleEntity rule, Resource resource) {
        return rule.getSubjectId() == null || rule.getSubjectId().equals(resource.id);
    }

    private void applyRunSubjectFilter(LambdaQueryWrapper<RunRecordEntity> query, AlertRuleEntity rule) {
        if (AlertSubjectType.COLLECTION_TASK.name().equals(rule.getSubjectType())) {
            query.isNotNull(RunRecordEntity::getCollectionTaskId)
                    .eq(rule.getSubjectId() != null, RunRecordEntity::getCollectionTaskId, rule.getSubjectId());
        } else if (AlertSubjectType.QUALITY_TASK.name().equals(rule.getSubjectType())) {
            query.isNotNull(RunRecordEntity::getQualityTaskId)
                    .eq(rule.getSubjectId() != null, RunRecordEntity::getQualityTaskId, rule.getSubjectId());
        } else {
            query.isNotNull(RunRecordEntity::getWorkflowDefinitionId)
                    .eq(rule.getSubjectId() != null, RunRecordEntity::getWorkflowDefinitionId, rule.getSubjectId());
        }
    }

    private void applyDispatchSubjectFilter(LambdaQueryWrapper<DispatchTaskEntity> query, AlertRuleEntity rule) {
        if (AlertSubjectType.COLLECTION_TASK.name().equals(rule.getSubjectType())) {
            query.eq(DispatchTaskEntity::getExecutionType, "COLLECTION_TASK")
                    .eq(rule.getSubjectId() != null, DispatchTaskEntity::getCollectionTaskId, rule.getSubjectId());
        } else if (AlertSubjectType.QUALITY_TASK.name().equals(rule.getSubjectType())) {
            query.eq(DispatchTaskEntity::getExecutionType, "QUALITY_TASK")
                    .eq(rule.getSubjectId() != null, DispatchTaskEntity::getQualityTaskId, rule.getSubjectId());
        } else {
            query.eq(DispatchTaskEntity::getExecutionType, "WORKFLOW_NODE")
                    .eq(rule.getSubjectId() != null, DispatchTaskEntity::getWorkflowDefinitionId, rule.getSubjectId());
        }
    }

    private Long runSourceId(AlertRuleEntity rule, RunRecordEntity run) {
        return AlertSubjectType.WORKFLOW.name().equals(rule.getSubjectType()) ? run.getWorkflowRunId() : run.getId();
    }

    private int intCondition(AlertRuleEntity rule, String name, int defaultValue) {
        Object value = rule.getConditionJson() == null ? null : rule.getConditionJson().get(name);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value.longValue();
    }

    private void putResourceClusterEvidence(Map<String, Object> evidence, Resource resource) {
        if (evidence == null || resource == null) {
            return;
        }
        evidence.put("targetClusterId", resource.runtimeClusterId);
        evidence.put("actualClusterId", resource.runtimeClusterId);
    }

    private static final class Resource {
        private final Long id;
        private final String key;
        private final String code;
        private final String name;
        private final Long ownerUserId;
        private final Long runtimeClusterId;

        private Resource(Long id, String key, String code, String name, Long ownerUserId) {
            this(id, key, code, name, ownerUserId, null);
        }

        private Resource(Long id, String key, String code, String name, Long ownerUserId, Long runtimeClusterId) {
            this.id = id;
            this.key = key;
            this.code = code;
            this.name = name;
            this.ownerUserId = ownerUserId;
            this.runtimeClusterId = runtimeClusterId;
        }

        private String key() {
            return StringUtils.hasText(key) ? key : String.valueOf(id);
        }
    }
}
