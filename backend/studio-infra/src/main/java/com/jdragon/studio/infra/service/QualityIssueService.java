package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.QualityIssueSeverity;
import com.jdragon.studio.dto.enums.QualityIssueStatus;
import com.jdragon.studio.dto.enums.QualityRuleGranularity;
import com.jdragon.studio.dto.model.QualityIssueDetailView;
import com.jdragon.studio.dto.model.QualityIssueTimelineEvent;
import com.jdragon.studio.dto.model.QualityIssueView;
import com.jdragon.studio.dto.model.QualityTaskDefinitionView;
import com.jdragon.studio.dto.model.dto.ExecutionEvent;
import com.jdragon.studio.dto.model.request.QualityIssueAssignRequest;
import com.jdragon.studio.dto.model.request.QualityIssueCommentRequest;
import com.jdragon.studio.dto.model.request.QualityIssueQueryRequest;
import com.jdragon.studio.dto.model.request.QualityIssueSeverityRequest;
import com.jdragon.studio.dto.model.request.QualityIssueStatusRequest;
import com.jdragon.studio.infra.entity.QualityIssueCommentEntity;
import com.jdragon.studio.infra.entity.QualityIssueEntity;
import com.jdragon.studio.infra.entity.QualityIssueEventEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.mapper.QualityIssueCommentMapper;
import com.jdragon.studio.infra.mapper.QualityIssueEventMapper;
import com.jdragon.studio.infra.mapper.QualityIssueMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class QualityIssueService {

    private static final Set<QualityIssueStatus> ACTIVE_STATUSES = new LinkedHashSet<QualityIssueStatus>(Arrays.asList(
            QualityIssueStatus.OPEN,
            QualityIssueStatus.ACKNOWLEDGED,
            QualityIssueStatus.INVESTIGATING,
            QualityIssueStatus.MITIGATED
    ));

    private final QualityIssueMapper issueMapper;
    private final QualityIssueCommentMapper commentMapper;
    private final QualityIssueEventMapper eventMapper;
    private final QualityTaskService qualityTaskService;
    private final StudioUserMapper userMapper;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final QualityIssueSeveritySupport severitySupport = new QualityIssueSeveritySupport();

    public QualityIssueService(QualityIssueMapper issueMapper,
                               QualityIssueCommentMapper commentMapper,
                               QualityIssueEventMapper eventMapper,
                               QualityTaskService qualityTaskService,
                               StudioUserMapper userMapper,
                               StudioSecurityService securityService,
                               ProjectResourceAccessService projectResourceAccessService) {
        this.issueMapper = issueMapper;
        this.commentMapper = commentMapper;
        this.eventMapper = eventMapper;
        this.qualityTaskService = qualityTaskService;
        this.userMapper = userMapper;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
    }

    @Transactional
    public void handleExecutionEvent(ExecutionEvent event, RunRecordEntity runRecord) {
        if (event == null || event.getQualityTaskId() == null) {
            return;
        }
        QualityTaskDefinitionView task = loadTask(event.getQualityTaskId());
        if (task == null) {
            return;
        }
        if ("FAILED".equalsIgnoreCase(event.getEventType())) {
            upsertFailureIssue(task, event, runRecord);
            return;
        }
        if (!"SUCCESS".equalsIgnoreCase(event.getEventType())) {
            return;
        }
        List<Map<String, Object>> alertDetails = extractAlertDetails(event.getPayload());
        Set<String> activeSignatures = new LinkedHashSet<String>();
        for (Map<String, Object> alertDetail : alertDetails) {
            QualityIssueEntity issue = upsertAlertIssue(task, event, runRecord, alertDetail);
            if (issue != null && issue.getSignature() != null) {
                activeSignatures.add(issue.getSignature());
            }
        }
        recordRecoverySignals(task, event, runRecord, activeSignatures);
    }

    public List<QualityIssueView> query(QualityIssueQueryRequest request) {
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            return new ArrayList<QualityIssueView>();
        }
        List<Long> taskIds = resolveMatchedTaskIds(request);
        if (usesTaskFilters(request) && taskIds.isEmpty()) {
            return new ArrayList<QualityIssueView>();
        }
        LambdaQueryWrapper<QualityIssueEntity> wrapper = new LambdaQueryWrapper<QualityIssueEntity>()
                .eq(QualityIssueEntity::getTenantId, securityService.currentTenantId())
                .eq(QualityIssueEntity::getProjectId, currentProjectId)
                .eq(request != null && request.getDatasourceId() != null, QualityIssueEntity::getDatasourceId, request == null ? null : request.getDatasourceId())
                .eq(request != null && request.getModelId() != null, QualityIssueEntity::getModelId, request == null ? null : request.getModelId())
                .eq(hasText(valueOf(request == null ? null : request.getRuleDimension())), QualityIssueEntity::getRuleDimension, normalizeCode(request == null ? null : request.getRuleDimension()))
                .eq(hasText(valueOf(request == null ? null : request.getGranularity())), QualityIssueEntity::getGranularity, normalizeCode(request == null ? null : request.getGranularity()))
                .eq(hasText(valueOf(request == null ? null : request.getSeverity())), QualityIssueEntity::getSeverity, normalizeCode(request == null ? null : request.getSeverity()))
                .eq(hasText(valueOf(request == null ? null : request.getStatus())), QualityIssueEntity::getStatus, normalizeCode(request == null ? null : request.getStatus()))
                .eq(request != null && request.getAssigneeUserId() != null, QualityIssueEntity::getAssigneeUserId, request == null ? null : request.getAssigneeUserId())
                .ge(request != null && request.getStartTime() != null, QualityIssueEntity::getLastSeenAt, request == null ? null : request.getStartTime())
                .le(request != null && request.getEndTime() != null, QualityIssueEntity::getLastSeenAt, request == null ? null : request.getEndTime())
                .in(taskIds != null && !taskIds.isEmpty(), QualityIssueEntity::getQualityTaskId, taskIds);
        List<QualityIssueView> items = new ArrayList<QualityIssueView>();
        for (QualityIssueEntity entity : issueMapper.selectList(wrapper)) {
            items.add(toView(entity));
        }
        Collections.sort(items, new Comparator<QualityIssueView>() {
            @Override
            public int compare(QualityIssueView left, QualityIssueView right) {
                int activeCompare = Boolean.compare(Boolean.TRUE.equals(right.getActive()), Boolean.TRUE.equals(left.getActive()));
                if (activeCompare != 0) {
                    return activeCompare;
                }
                int severityCompare = Integer.compare(severitySupport.severityRank(right.getSeverity()), severitySupport.severityRank(left.getSeverity()));
                if (severityCompare != 0) {
                    return severityCompare;
                }
                int overdueCompare = Boolean.compare(Boolean.TRUE.equals(right.getOverdue()), Boolean.TRUE.equals(left.getOverdue()));
                if (overdueCompare != 0) {
                    return overdueCompare;
                }
                return compareTimeDesc(left.getLastSeenAt(), right.getLastSeenAt());
            }
        });
        return items;
    }

    public QualityIssueDetailView get(Long id) {
        QualityIssueEntity entity = requireIssue(id);
        QualityIssueDetailView detail = toDetail(entity);
        List<QualityIssueTimelineEvent> timeline = new ArrayList<QualityIssueTimelineEvent>();
        for (QualityIssueEventEntity item : eventMapper.selectList(new LambdaQueryWrapper<QualityIssueEventEntity>()
                .eq(QualityIssueEventEntity::getTenantId, entity.getTenantId())
                .eq(QualityIssueEventEntity::getProjectId, entity.getProjectId())
                .eq(QualityIssueEventEntity::getIssueId, entity.getId())
                .orderByDesc(QualityIssueEventEntity::getCreatedAt)
                .orderByDesc(QualityIssueEventEntity::getId))) {
            timeline.add(toTimeline(item));
        }
        detail.setTimeline(timeline);
        return detail;
    }

    @Transactional
    public QualityIssueDetailView assign(Long id, QualityIssueAssignRequest request) {
        QualityIssueEntity entity = requireIssue(id);
        Long assigneeUserId = request == null ? null : request.getAssigneeUserId();
        entity.setAssigneeUserId(assigneeUserId);
        entity.setAssigneeNameSnapshot(resolveUserDisplayName(assigneeUserId));
        issueMapper.updateById(entity);
        recordIssueEvent(entity, "ASSIGNED", "更新负责人",
                assigneeUserId == null ? "已清空负责人" : "负责人已更新为 " + safeText(entity.getAssigneeNameSnapshot(), String.valueOf(assigneeUserId)),
                null);
        return get(id);
    }

    @Transactional
    public QualityIssueDetailView updateStatus(Long id, QualityIssueStatusRequest request) {
        QualityIssueEntity entity = requireIssue(id);
        QualityIssueStatus previous = parseStatus(entity.getStatus());
        QualityIssueStatus current = parseStatus(request == null ? null : request.getStatus());
        if (current == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Issue status is required");
        }
        if (current == QualityIssueStatus.RESOLVED || current == QualityIssueStatus.FALSE_POSITIVE) {
            entity.setConsecutiveFailureCount(Integer.valueOf(0));
            entity.setLastRunStatus("SUCCESS");
            entity.setLastRecoveryAt(LocalDateTime.now());
        } else if (isClosedStatus(previous) && isActiveStatus(current)) {
            entity.setReopenCount(Integer.valueOf(safeInt(entity.getReopenCount()) + 1));
            entity.setSlaDueAt(LocalDateTime.now().plus(resolveSlaDuration(parseSeverity(entity.getSeverity()))));
        }
        entity.setStatus(current.name());
        issueMapper.updateById(entity);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("previousStatus", previous == null ? null : previous.name());
        metadata.put("currentStatus", current.name());
        recordIssueEvent(entity, "STATUS_CHANGED", "状态更新",
                transitionMessage("状态", previous == null ? null : previous.name(), current.name(), request == null ? null : request.getComment()),
                metadata);
        return get(id);
    }

    @Transactional
    public QualityIssueDetailView updateSeverity(Long id, QualityIssueSeverityRequest request) {
        QualityIssueEntity entity = requireIssue(id);
        QualityIssueSeverity previous = parseSeverity(entity.getSeverity());
        QualityIssueSeverity current = parseSeverity(request == null ? null : request.getSeverity());
        if (current == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Issue severity is required");
        }
        entity.setManualSeverity(current.name());
        entity.setSeverity(current.name());
        if (isActiveStatus(parseStatus(entity.getStatus()))) {
            entity.setSlaDueAt(LocalDateTime.now().plus(resolveSlaDuration(current)));
        }
        issueMapper.updateById(entity);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("previousSeverity", previous == null ? null : previous.name());
        metadata.put("currentSeverity", current.name());
        recordIssueEvent(entity, "SEVERITY_CHANGED", "严重级别更新",
                transitionMessage("严重级别", previous == null ? null : previous.name(), current.name(), request == null ? null : request.getComment()),
                metadata);
        return get(id);
    }

    @Transactional
    public QualityIssueDetailView addComment(Long id, QualityIssueCommentRequest request) {
        QualityIssueEntity entity = requireIssue(id);
        String content = normalizeText(request == null ? null : request.getContent());
        if (!hasText(content)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Comment content is required");
        }
        QualityIssueCommentEntity comment = new QualityIssueCommentEntity();
        comment.setTenantId(entity.getTenantId());
        comment.setProjectId(entity.getProjectId());
        comment.setIssueId(entity.getId());
        comment.setAuthorUserId(securityService.currentUserId());
        comment.setAuthorNameSnapshot(currentActorName());
        comment.setContent(content);
        commentMapper.insert(comment);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("commentId", comment.getId());
        recordIssueEvent(entity, "COMMENT", "新增评论", content, metadata);
        return get(id);
    }

    public List<QualityIssueEntity> listProjectIssues() {
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            return new ArrayList<QualityIssueEntity>();
        }
        return issueMapper.selectList(new LambdaQueryWrapper<QualityIssueEntity>()
                .eq(QualityIssueEntity::getTenantId, securityService.currentTenantId())
                .eq(QualityIssueEntity::getProjectId, currentProjectId));
    }

    public List<QualityIssueEventEntity> listProjectIssueEvents(LocalDateTime startTime, LocalDateTime endTime) {
        Long currentProjectId = projectResourceAccessService.currentProjectId();
        if (currentProjectId == null) {
            return new ArrayList<QualityIssueEventEntity>();
        }
        return eventMapper.selectList(new LambdaQueryWrapper<QualityIssueEventEntity>()
                .eq(QualityIssueEventEntity::getTenantId, securityService.currentTenantId())
                .eq(QualityIssueEventEntity::getProjectId, currentProjectId)
                .ge(startTime != null, QualityIssueEventEntity::getCreatedAt, startTime)
                .le(endTime != null, QualityIssueEventEntity::getCreatedAt, endTime));
    }

    private QualityIssueEntity upsertFailureIssue(QualityTaskDefinitionView task, ExecutionEvent event, RunRecordEntity runRecord) {
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("error", extractMessage(event == null ? null : event.getPayload(), runRecord));
        evidence.put("resolvedSql", extractString(event == null ? null : event.getPayload(), "resolvedSql"));
        evidence.put("runRecordId", runRecord == null ? null : runRecord.getId());
        return upsertIssue(task, runRecord, "EXECUTION_FAILURE", null, resolveBaseSeverity(task, true),
                extractMessage(event == null ? null : event.getPayload(), runRecord),
                buildTitle(task, "EXECUTION_FAILURE", null), evidence);
    }

    private QualityIssueEntity upsertAlertIssue(QualityTaskDefinitionView task,
                                                ExecutionEvent event,
                                                RunRecordEntity runRecord,
                                                Map<String, Object> alertDetail) {
        String outputField = asText(alertDetail == null ? null : alertDetail.get("resultField"));
        String message = asText(alertDetail == null ? null : alertDetail.get("message"));
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        if (alertDetail != null) {
            evidence.putAll(alertDetail);
        }
        evidence.put("resolvedSql", extractString(event == null ? null : event.getPayload(), "resolvedSql"));
        evidence.put("runRecordId", runRecord == null ? null : runRecord.getId());
        return upsertIssue(task, runRecord, "ALERT", outputField, resolveBaseSeverity(task, false),
                hasText(message) ? message : extractMessage(event == null ? null : event.getPayload(), runRecord),
                buildTitle(task, "ALERT", outputField), evidence);
    }

    private QualityIssueEntity upsertIssue(QualityTaskDefinitionView task,
                                           RunRecordEntity runRecord,
                                           String issueType,
                                           String outputField,
                                           QualityIssueSeverity baseSeverity,
                                           String latestMessage,
                                           String title,
                                           Map<String, Object> evidence) {
        LocalDateTime happenedAt = resolveWhen(runRecord);
        String signature = buildSignature(task, issueType, outputField);
        QualityIssueEntity entity = issueMapper.selectOne(new LambdaQueryWrapper<QualityIssueEntity>()
                .eq(QualityIssueEntity::getTenantId, task.getTenantId())
                .eq(QualityIssueEntity::getProjectId, task.getProjectId())
                .eq(QualityIssueEntity::getSignature, signature)
                .last("limit 1"));
        boolean created = false;
        boolean reopened = false;
        if (entity == null) {
            created = true;
            entity = new QualityIssueEntity();
            entity.setTenantId(task.getTenantId());
            entity.setProjectId(task.getProjectId());
            entity.setSignature(signature);
            entity.setIssueType(issueType);
            entity.setQualityTaskId(task.getId());
            entity.setQualityTaskNameSnapshot(task.getTaskName());
            entity.setRuleId(task.getRuleId());
            entity.setRuleNameSnapshot(task.getRuleName());
            entity.setRuleDimension(task.getRuleDimension() == null ? null : task.getRuleDimension().name());
            entity.setDatasourceId(task.getDatasourceId());
            entity.setDatasourceNameSnapshot(task.getDatasourceName());
            entity.setDatasourceTypeCode(task.getDatasourceTypeCode());
            entity.setModelId(task.getModelId());
            entity.setModelNameSnapshot(task.getModelName());
            entity.setModelPhysicalLocator(task.getModelPhysicalLocator());
            entity.setColumnName(task.getColumnName());
            entity.setOutputField(outputField);
            entity.setGranularity(task.getGranularity() == null ? null : task.getGranularity().name());
            entity.setTitle(title);
            entity.setStatus(QualityIssueStatus.OPEN.name());
            entity.setFirstSeenAt(happenedAt);
            entity.setOccurrenceCount(Integer.valueOf(0));
            entity.setConsecutiveFailureCount(Integer.valueOf(0));
            entity.setReopenCount(Integer.valueOf(0));
        } else if (isClosedStatus(parseStatus(entity.getStatus()))) {
            entity.setStatus(QualityIssueStatus.OPEN.name());
            entity.setReopenCount(Integer.valueOf(safeInt(entity.getReopenCount()) + 1));
            reopened = true;
        }
        entity.setTitle(title);
        entity.setOccurrenceCount(Integer.valueOf(safeInt(entity.getOccurrenceCount()) + 1));
        entity.setConsecutiveFailureCount(Integer.valueOf(safeInt(entity.getConsecutiveFailureCount()) + 1));
        entity.setLatestMessage(latestMessage);
        entity.setLastSeenAt(happenedAt);
        entity.setLastRecoveryAt(null);
        entity.setLastRunRecordId(runRecord == null ? null : runRecord.getId());
        entity.setLastRunStatus("FAILED");
        entity.setCurrentEvidenceJson(copyMap(evidence));
        QualityIssueSeverity systemSeverity = effectiveSystemSeverity(baseSeverity, entity);
        entity.setSystemSeverity(systemSeverity.name());
        entity.setSeverity(displaySeverity(entity, systemSeverity).name());
        entity.setSlaDueAt(happenedAt.plus(resolveSlaDuration(parseSeverity(entity.getSeverity()))));
        if (created) {
            issueMapper.insert(entity);
            entity.setIssueCode("QI-" + entity.getId());
            issueMapper.updateById(entity);
            recordIssueEvent(entity, "CREATED", "创建问题", latestMessage, evidence);
        } else {
            issueMapper.updateById(entity);
            recordIssueEvent(entity, reopened ? "REOPENED" : "DETECTED", reopened ? "问题重新打开" : "问题再次触发", latestMessage, evidence);
        }
        return entity;
    }

    private void recordRecoverySignals(QualityTaskDefinitionView task,
                                       ExecutionEvent event,
                                       RunRecordEntity runRecord,
                                       Set<String> activeSignatures) {
        for (QualityIssueEntity entity : issueMapper.selectList(new LambdaQueryWrapper<QualityIssueEntity>()
                .eq(QualityIssueEntity::getTenantId, task.getTenantId())
                .eq(QualityIssueEntity::getProjectId, task.getProjectId())
                .eq(QualityIssueEntity::getQualityTaskId, task.getId()))) {
            if (!isActiveStatus(parseStatus(entity.getStatus()))) {
                continue;
            }
            if (activeSignatures.contains(entity.getSignature()) || "SUCCESS".equalsIgnoreCase(entity.getLastRunStatus())) {
                continue;
            }
            entity.setLastRunStatus("SUCCESS");
            entity.setLastRunRecordId(runRecord == null ? null : runRecord.getId());
            entity.setLastRecoveryAt(resolveWhen(runRecord));
            issueMapper.updateById(entity);
            Map<String, Object> metadata = new LinkedHashMap<String, Object>();
            metadata.put("runRecordId", runRecord == null ? null : runRecord.getId());
            metadata.put("resolvedSql", extractString(event == null ? null : event.getPayload(), "resolvedSql"));
            recordIssueEvent(entity, "RECOVERY_DETECTED", "检测到恢复", "本次执行未再次命中该问题。", metadata);
        }
    }

    private QualityIssueEntity requireIssue(Long id) {
        QualityIssueEntity entity = issueMapper.selectById(id);
        if (entity == null
                || !securityService.currentTenantId().equals(entity.getTenantId())
                || (projectResourceAccessService.currentProjectId() != null
                && !projectResourceAccessService.currentProjectId().equals(entity.getProjectId()))) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Quality issue not found: " + id);
        }
        return entity;
    }

    private QualityIssueView toView(QualityIssueEntity entity) {
        QualityIssueView view = new QualityIssueView();
        view.setId(entity.getId());
        view.setIssueCode(entity.getIssueCode());
        view.setIssueType(entity.getIssueType());
        view.setTitle(entity.getTitle());
        view.setSeverity(parseSeverity(entity.getSeverity()));
        view.setSystemSeverity(parseSeverity(entity.getSystemSeverity()));
        view.setManualSeverity(parseSeverity(entity.getManualSeverity()));
        view.setStatus(parseStatus(entity.getStatus()));
        view.setAssetId(assetId(entity.getDatasourceId(), entity.getModelId()));
        view.setDatasourceId(entity.getDatasourceId());
        view.setDatasourceName(entity.getDatasourceNameSnapshot());
        view.setDatasourceTypeCode(entity.getDatasourceTypeCode());
        view.setModelId(entity.getModelId());
        view.setModelName(entity.getModelNameSnapshot());
        view.setModelPhysicalLocator(entity.getModelPhysicalLocator());
        view.setQualityTaskId(entity.getQualityTaskId());
        view.setQualityTaskName(entity.getQualityTaskNameSnapshot());
        view.setRuleId(entity.getRuleId());
        view.setRuleName(entity.getRuleNameSnapshot());
        view.setRuleDimension(entity.getRuleDimension());
        view.setGranularity(entity.getGranularity());
        view.setColumnName(entity.getColumnName());
        view.setOutputField(entity.getOutputField());
        view.setFirstSeenAt(entity.getFirstSeenAt());
        view.setLastSeenAt(entity.getLastSeenAt());
        view.setLastRecoveryAt(entity.getLastRecoveryAt());
        view.setSlaDueAt(entity.getSlaDueAt());
        view.setAssigneeUserId(entity.getAssigneeUserId());
        view.setAssigneeName(entity.getAssigneeNameSnapshot());
        view.setLatestMessage(entity.getLatestMessage());
        view.setLastRunRecordId(entity.getLastRunRecordId());
        view.setLastRunStatus(entity.getLastRunStatus());
        view.setOccurrenceCount(entity.getOccurrenceCount());
        view.setConsecutiveFailureCount(entity.getConsecutiveFailureCount());
        view.setReopenCount(entity.getReopenCount());
        view.setActive(Boolean.valueOf(isActiveStatus(parseStatus(entity.getStatus()))));
        view.setOverdue(Boolean.valueOf(isOverdue(entity)));
        return view;
    }

    private QualityIssueDetailView toDetail(QualityIssueEntity entity) {
        QualityIssueView base = toView(entity);
        QualityIssueDetailView detail = new QualityIssueDetailView();
        detail.setId(base.getId());
        detail.setIssueCode(base.getIssueCode());
        detail.setIssueType(base.getIssueType());
        detail.setTitle(base.getTitle());
        detail.setSeverity(base.getSeverity());
        detail.setSystemSeverity(base.getSystemSeverity());
        detail.setManualSeverity(base.getManualSeverity());
        detail.setStatus(base.getStatus());
        detail.setAssetId(base.getAssetId());
        detail.setDatasourceId(base.getDatasourceId());
        detail.setDatasourceName(base.getDatasourceName());
        detail.setDatasourceTypeCode(base.getDatasourceTypeCode());
        detail.setModelId(base.getModelId());
        detail.setModelName(base.getModelName());
        detail.setModelPhysicalLocator(base.getModelPhysicalLocator());
        detail.setQualityTaskId(base.getQualityTaskId());
        detail.setQualityTaskName(base.getQualityTaskName());
        detail.setRuleId(base.getRuleId());
        detail.setRuleName(base.getRuleName());
        detail.setRuleDimension(base.getRuleDimension());
        detail.setGranularity(base.getGranularity());
        detail.setColumnName(base.getColumnName());
        detail.setOutputField(base.getOutputField());
        detail.setFirstSeenAt(base.getFirstSeenAt());
        detail.setLastSeenAt(base.getLastSeenAt());
        detail.setLastRecoveryAt(base.getLastRecoveryAt());
        detail.setSlaDueAt(base.getSlaDueAt());
        detail.setAssigneeUserId(base.getAssigneeUserId());
        detail.setAssigneeName(base.getAssigneeName());
        detail.setLatestMessage(base.getLatestMessage());
        detail.setLastRunRecordId(base.getLastRunRecordId());
        detail.setLastRunStatus(base.getLastRunStatus());
        detail.setOccurrenceCount(base.getOccurrenceCount());
        detail.setConsecutiveFailureCount(base.getConsecutiveFailureCount());
        detail.setReopenCount(base.getReopenCount());
        detail.setOverdue(base.getOverdue());
        detail.setActive(base.getActive());
        detail.setCurrentEvidence(copyMap(entity.getCurrentEvidenceJson()));
        return detail;
    }

    private QualityIssueTimelineEvent toTimeline(QualityIssueEventEntity entity) {
        QualityIssueTimelineEvent event = new QualityIssueTimelineEvent();
        event.setId(entity.getId());
        event.setEventType(entity.getEventType());
        event.setTitle(entity.getEventTitle());
        event.setMessage(entity.getEventMessage());
        event.setActorUserId(entity.getActorUserId());
        event.setActorName(entity.getActorNameSnapshot());
        event.setCreatedAt(entity.getCreatedAt());
        event.setMetadata(copyMap(entity.getMetadataJson()));
        return event;
    }

    private void recordIssueEvent(QualityIssueEntity issue,
                                  String eventType,
                                  String title,
                                  String message,
                                  Map<String, Object> metadata) {
        QualityIssueEventEntity entity = new QualityIssueEventEntity();
        entity.setTenantId(issue.getTenantId());
        entity.setProjectId(issue.getProjectId());
        entity.setIssueId(issue.getId());
        entity.setEventType(eventType);
        entity.setEventTitle(title);
        entity.setEventMessage(message);
        entity.setActorUserId(securityService.currentUserId());
        entity.setActorNameSnapshot(currentActorName());
        entity.setMetadataJson(copyMap(metadata));
        eventMapper.insert(entity);
    }

    private QualityTaskDefinitionView loadTask(Long qualityTaskId) {
        try {
            return qualityTaskService.get(qualityTaskId);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<Long> resolveMatchedTaskIds(QualityIssueQueryRequest request) {
        if (!usesTaskFilters(request)) {
            return null;
        }
        List<Long> ids = new ArrayList<Long>();
        for (QualityTaskDefinitionView task : qualityTaskService.list(null,
                request == null ? null : request.getTaskStatus(),
                request == null ? null : request.getRuleDimension(),
                request == null ? null : request.getGranularity())) {
            if (request != null && request.getDatasourceId() != null && !request.getDatasourceId().equals(task.getDatasourceId())) {
                continue;
            }
            if (request != null && request.getModelId() != null && !request.getModelId().equals(task.getModelId())) {
                continue;
            }
            if (task.getId() != null) {
                ids.add(task.getId());
            }
        }
        return ids;
    }

    private boolean usesTaskFilters(QualityIssueQueryRequest request) {
        return request != null && (hasText(valueOf(request.getTaskStatus()))
                || hasText(valueOf(request.getRuleDimension()))
                || hasText(valueOf(request.getGranularity()))
                || request.getDatasourceId() != null
                || request.getModelId() != null);
    }

    private QualityIssueSeverity resolveBaseSeverity(QualityTaskDefinitionView task, boolean failure) {
        if (failure) {
            return QualityIssueSeverity.HIGH;
        }
        return task != null && task.getGranularity() == QualityRuleGranularity.COLUMN
                ? QualityIssueSeverity.MEDIUM
                : QualityIssueSeverity.HIGH;
    }

    private QualityIssueSeverity effectiveSystemSeverity(QualityIssueSeverity base, QualityIssueEntity entity) {
        QualityIssueSeverity result = base == null ? QualityIssueSeverity.MEDIUM : base;
        if (safeInt(entity.getConsecutiveFailureCount()) >= 3) {
            result = escalate(result);
        }
        if (entity.getSlaDueAt() != null && entity.getSlaDueAt().isBefore(LocalDateTime.now())) {
            result = escalate(result);
        }
        return result;
    }

    private QualityIssueSeverity displaySeverity(QualityIssueEntity entity, QualityIssueSeverity systemSeverity) {
        QualityIssueSeverity manual = parseSeverity(entity.getManualSeverity());
        return manual == null ? systemSeverity : manual;
    }

    private Duration resolveSlaDuration(QualityIssueSeverity severity) {
        if (severity == QualityIssueSeverity.CRITICAL) {
            return Duration.ofHours(4);
        }
        if (severity == QualityIssueSeverity.HIGH) {
            return Duration.ofHours(24);
        }
        if (severity == QualityIssueSeverity.LOW) {
            return Duration.ofDays(7);
        }
        return Duration.ofDays(3);
    }

    private boolean isClosedStatus(QualityIssueStatus status) {
        return status == QualityIssueStatus.RESOLVED || status == QualityIssueStatus.FALSE_POSITIVE;
    }

    private boolean isActiveStatus(QualityIssueStatus status) {
        return status != null && ACTIVE_STATUSES.contains(status);
    }

    private boolean isOverdue(QualityIssueEntity entity) {
        return entity != null
                && isActiveStatus(parseStatus(entity.getStatus()))
                && entity.getSlaDueAt() != null
                && entity.getSlaDueAt().isBefore(LocalDateTime.now());
    }

    private QualityIssueSeverity escalate(QualityIssueSeverity severity) {
        if (severity == QualityIssueSeverity.LOW) {
            return QualityIssueSeverity.MEDIUM;
        }
        if (severity == QualityIssueSeverity.MEDIUM) {
            return QualityIssueSeverity.HIGH;
        }
        return QualityIssueSeverity.CRITICAL;
    }

    private String buildSignature(QualityTaskDefinitionView task, String issueType, String outputField) {
        return safeText(valueOf(task == null ? null : task.getProjectId()), "")
                + "|" + safeText(valueOf(task == null ? null : task.getId()), "")
                + "|" + safeText(valueOf(task == null ? null : task.getRuleId()), "")
                + "|" + safeText(valueOf(task == null ? null : task.getDatasourceId()), "")
                + "|" + safeText(valueOf(task == null ? null : task.getModelId()), "")
                + "|" + safeText(task == null ? null : task.getColumnName(), "")
                + "|" + safeText(outputField, "")
                + "|" + safeText(task == null || task.getGranularity() == null ? null : task.getGranularity().name(), "")
                + "|" + safeText(issueType, "");
    }

    private String buildTitle(QualityTaskDefinitionView task, String issueType, String outputField) {
        if ("EXECUTION_FAILURE".equalsIgnoreCase(issueType)) {
            return safeText(task == null ? null : task.getTaskName(), "质量任务") + " 执行失败";
        }
        if (hasText(outputField)) {
            return safeText(task == null ? null : task.getRuleName(), "质量规则") + " 命中输出字段 " + outputField;
        }
        return safeText(task == null ? null : task.getRuleName(), "质量规则") + " 命中告警";
    }

    private String transitionMessage(String label, String previous, String current, String comment) {
        StringBuilder builder = new StringBuilder();
        builder.append(label).append("由 ").append(safeText(previous, "-")).append(" 调整为 ").append(safeText(current, "-"));
        if (hasText(comment)) {
            builder.append("。备注：").append(comment.trim());
        }
        return builder.toString();
    }

    private String currentActorName() {
        String displayName = resolveUserDisplayName(securityService.currentUserId());
        return hasText(displayName) ? displayName : safeText(securityService.currentUsername(), "system");
    }

    private String resolveUserDisplayName(Long userId) {
        if (userId == null) {
            return null;
        }
        StudioUserEntity entity = userMapper.selectById(userId);
        if (entity == null) {
            return null;
        }
        return hasText(entity.getDisplayName()) ? entity.getDisplayName().trim() : entity.getUsername();
    }

    private List<Map<String, Object>> extractAlertDetails(Map<String, Object> payload) {
        Object alertDetails = payload == null ? null : payload.get("alertDetails");
        if (!(alertDetails instanceof List<?>)) {
            return new ArrayList<Map<String, Object>>();
        }
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (Object item : (List<?>) alertDetails) {
            Map<String, Object> detail = copyObjectMap(item);
            if (detail != null) {
                items.add(detail);
            }
        }
        return items;
    }

    private Map<String, Object> copyObjectMap(Object candidate) {
        if (!(candidate instanceof Map<?, ?>)) {
            return null;
        }
        Map<?, ?> source = (Map<?, ?>) candidate;
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return copy;
    }

    private String extractMessage(Map<String, Object> payload, RunRecordEntity runRecord) {
        String error = extractString(payload, "error");
        if (hasText(error)) {
            return error;
        }
        String message = extractString(payload, "message");
        if (hasText(message)) {
            return message;
        }
        return runRecord == null ? null : runRecord.getMessage();
    }

    private String extractString(Map<String, Object> payload, String key) {
        return payload == null || key == null || payload.get(key) == null ? null : String.valueOf(payload.get(key));
    }

    private LocalDateTime resolveWhen(RunRecordEntity runRecord) {
        if (runRecord != null && runRecord.getEndedAt() != null) {
            return runRecord.getEndedAt();
        }
        if (runRecord != null && runRecord.getUpdatedAt() != null) {
            return runRecord.getUpdatedAt();
        }
        return LocalDateTime.now();
    }

    private String assetId(Long datasourceId, Long modelId) {
        if (datasourceId == null && modelId == null) {
            return null;
        }
        return String.valueOf(datasourceId == null ? 0L : datasourceId) + ":" + String.valueOf(modelId == null ? 0L : modelId);
    }

    private Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(source);
    }

    private String normalizeCode(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private String normalizeText(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String safeText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private String valueOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value.intValue();
    }

    private int compareTimeDesc(LocalDateTime left, LocalDateTime right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return right.compareTo(left);
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private QualityIssueStatus parseStatus(String value) {
        try {
            return hasText(value) ? QualityIssueStatus.valueOf(value.trim().toUpperCase(Locale.ROOT)) : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private QualityIssueSeverity parseSeverity(String value) {
        try {
            return hasText(value) ? QualityIssueSeverity.valueOf(value.trim().toUpperCase(Locale.ROOT)) : null;
        } catch (Exception ex) {
            return null;
        }
    }

}
