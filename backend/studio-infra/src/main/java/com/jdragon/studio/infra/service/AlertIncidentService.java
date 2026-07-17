package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.AlertChannelType;
import com.jdragon.studio.dto.enums.AlertDeliveryStatus;
import com.jdragon.studio.dto.enums.AlertEventType;
import com.jdragon.studio.dto.enums.AlertIncidentStatus;
import com.jdragon.studio.dto.model.AlertDeliveryView;
import com.jdragon.studio.dto.model.AlertEventView;
import com.jdragon.studio.dto.model.AlertIncidentView;
import com.jdragon.studio.dto.model.AlertSummaryView;
import com.jdragon.studio.dto.model.AlertTenantProjectSummaryView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.AlertIncidentActionRequest;
import com.jdragon.studio.dto.model.request.AlertIncidentQueryRequest;
import com.jdragon.studio.dto.model.request.AlertTenantSummaryQueryRequest;
import com.jdragon.studio.infra.entity.AlertChannelEntity;
import com.jdragon.studio.infra.entity.AlertDeliveryEntity;
import com.jdragon.studio.infra.entity.AlertEventEntity;
import com.jdragon.studio.infra.entity.AlertIncidentEntity;
import com.jdragon.studio.infra.entity.AlertRuleEntity;
import com.jdragon.studio.infra.mapper.AlertChannelMapper;
import com.jdragon.studio.infra.mapper.AlertDeliveryMapper;
import com.jdragon.studio.infra.mapper.AlertEventMapper;
import com.jdragon.studio.infra.mapper.AlertIncidentMapper;
import com.jdragon.studio.infra.mapper.AlertRuleMapper;
import com.jdragon.studio.infra.mapper.ProjectMapper;
import com.jdragon.studio.infra.model.AlertObservation;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class AlertIncidentService {

    private static final int MAX_STATE_RETRIES = 5;

    private final AlertIncidentMapper alertIncidentMapper;
    private final AlertEventMapper alertEventMapper;
    private final AlertDeliveryMapper alertDeliveryMapper;
    private final AlertRuleMapper alertRuleMapper;
    private final AlertChannelMapper alertChannelMapper;
    private final AlertRuleService alertRuleService;
    private final AlertRecipientResolver recipientResolver;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final AlertIncidentPresentationSupport presentationSupport;
    private final AlertIncidentSummarySupport summarySupport;
    private TransactionTemplate stateTransactionTemplate;
    private AlertChannelService alertChannelService;

    public AlertIncidentService(AlertIncidentMapper alertIncidentMapper,
                                AlertEventMapper alertEventMapper,
                                AlertDeliveryMapper alertDeliveryMapper,
                                AlertRuleMapper alertRuleMapper,
                                AlertChannelMapper alertChannelMapper,
                                ProjectMapper projectMapper,
                                AlertRuleService alertRuleService,
                                AlertRecipientResolver recipientResolver,
                                StudioSecurityService securityService,
                                ProjectResourceAccessService projectResourceAccessService) {
        this.alertIncidentMapper = alertIncidentMapper;
        this.alertEventMapper = alertEventMapper;
        this.alertDeliveryMapper = alertDeliveryMapper;
        this.alertRuleMapper = alertRuleMapper;
        this.alertChannelMapper = alertChannelMapper;
        this.alertRuleService = alertRuleService;
        this.recipientResolver = recipientResolver;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.presentationSupport = new AlertIncidentPresentationSupport(alertEventMapper, alertDeliveryMapper);
        this.summarySupport = new AlertIncidentSummarySupport(alertIncidentMapper, alertDeliveryMapper, alertRuleMapper,
                projectMapper, securityService, projectResourceAccessService);
    }

    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.stateTransactionTemplate = template;
    }

    @Autowired(required = false)
    void setAlertChannelService(AlertChannelService alertChannelService) {
        this.alertChannelService = alertChannelService;
    }

    public PageView<AlertIncidentView> query(AlertIncidentQueryRequest request) {
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        int pageNo = pageNo(request == null ? null : request.getPageNo());
        int pageSize = pageSize(request == null ? null : request.getPageSize());
        LambdaQueryWrapper<AlertIncidentEntity> query = new LambdaQueryWrapper<AlertIncidentEntity>()
                .eq(AlertIncidentEntity::getTenantId, securityService.currentTenantId())
                .eq(AlertIncidentEntity::getProjectId, projectId)
                .eq(request != null && StringUtils.hasText(request.getStatus()), AlertIncidentEntity::getStatus, upper(request == null ? null : request.getStatus()))
                .eq(request != null && StringUtils.hasText(request.getSeverity()), AlertIncidentEntity::getSeverity, upper(request == null ? null : request.getSeverity()))
                .eq(request != null && StringUtils.hasText(request.getRuleType()), AlertIncidentEntity::getRuleType, upper(request == null ? null : request.getRuleType()))
                .eq(request != null && StringUtils.hasText(request.getSubjectType()), AlertIncidentEntity::getSubjectType, upper(request == null ? null : request.getSubjectType()))
                .in(request != null && Boolean.TRUE.equals(request.getActiveOnly()), AlertIncidentEntity::getStatus,
                        AlertIncidentStatus.OPEN.name(), AlertIncidentStatus.ACKNOWLEDGED.name())
                .ge(request != null && request.getStartTime() != null, AlertIncidentEntity::getLastTriggeredAt, request == null ? null : request.getStartTime())
                .le(request != null && request.getEndTime() != null, AlertIncidentEntity::getLastTriggeredAt, request == null ? null : request.getEndTime());
        if (request != null && StringUtils.hasText(request.getKeyword())) {
            String keyword = request.getKeyword().trim();
            query.and(wrapper -> wrapper.like(AlertIncidentEntity::getRuleNameSnapshot, keyword)
                    .or().like(AlertIncidentEntity::getSubjectNameSnapshot, keyword)
                    .or().like(AlertIncidentEntity::getSummary, keyword));
        }
        Long total = alertIncidentMapper.selectCount(query);
        List<AlertIncidentEntity> entities = alertIncidentMapper.selectList(query
                .orderByAsc(AlertIncidentEntity::getStatus)
                .orderByDesc(AlertIncidentEntity::getLastTriggeredAt)
                .orderByDesc(AlertIncidentEntity::getId)
                .last("limit " + ((pageNo - 1) * pageSize) + "," + pageSize));
        List<AlertIncidentView> items = new ArrayList<AlertIncidentView>();
        for (AlertIncidentEntity entity : entities) {
            items.add(toView(entity, false));
        }
        return PageView.of(pageNo, pageSize, total == null ? 0L : total.longValue(), items);
    }

    public AlertIncidentView get(Long id) {
        return toView(requireCurrentProjectIncident(id), true);
    }

    public PageView<AlertEventView> events(Long incidentId, Integer pageNoValue, Integer pageSizeValue) {
        AlertIncidentEntity incident = requireCurrentProjectIncident(incidentId);
        int pageNo = pageNo(pageNoValue);
        int pageSize = pageSize(pageSizeValue);
        LambdaQueryWrapper<AlertEventEntity> query = new LambdaQueryWrapper<AlertEventEntity>()
                .eq(AlertEventEntity::getTenantId, incident.getTenantId())
                .eq(AlertEventEntity::getProjectId, incident.getProjectId())
                .eq(AlertEventEntity::getIncidentId, incident.getId());
        Long total = alertEventMapper.selectCount(query);
        List<AlertEventEntity> entities = alertEventMapper.selectList(query.orderByDesc(AlertEventEntity::getObservedAt)
                .orderByDesc(AlertEventEntity::getId)
                .last("limit " + ((pageNo - 1) * pageSize) + "," + pageSize));
        List<AlertEventView> items = new ArrayList<AlertEventView>();
        for (AlertEventEntity entity : entities) {
            items.add(toEventView(entity));
        }
        return PageView.of(pageNo, pageSize, total == null ? 0L : total.longValue(), items);
    }

    @Transactional
    public AlertIncidentView acknowledge(Long id, AlertIncidentActionRequest request) {
        AlertIncidentEntity incident = requireCurrentProjectIncident(id);
        if (!AlertIncidentStatus.OPEN.name().equals(incident.getStatus())) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR, "Only open alert incidents can be acknowledged");
        }
        String previous = incident.getStatus();
        LocalDateTime now = LocalDateTime.now();
        incident.setStatus(AlertIncidentStatus.ACKNOWLEDGED.name());
        incident.setAcknowledgedAt(now);
        incident.setAcknowledgedBy(securityService.currentUserId());
        if (alertIncidentMapper.updateById(incident) == 0) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "Alert incident changed concurrently; refresh and retry");
        }
        createEvent(incident, null, AlertEventType.ACKNOWLEDGED, previous, incident.getStatus(),
                "MANUAL", String.valueOf(incident.getId()), uniqueKey("ack", incident.getId()),
                actionSummary("告警已确认", request), Collections.<String, Object>emptyMap(), now, false, null);
        return toView(incident, true);
    }

    @Transactional
    public AlertIncidentView close(Long id, AlertIncidentActionRequest request) {
        AlertIncidentEntity incident = requireCurrentProjectIncident(id);
        if (AlertIncidentStatus.CLOSED.name().equals(incident.getStatus())) {
            return toView(incident, true);
        }
        String previous = incident.getStatus();
        LocalDateTime now = LocalDateTime.now();
        incident.setStatus(AlertIncidentStatus.CLOSED.name());
        incident.setClosedAt(now);
        incident.setClosedBy(securityService.currentUserId());
        incident.setClosedWhileActive(Integer.valueOf(1).equals(incident.getConditionActive()) ? 1 : 0);
        if (alertIncidentMapper.updateById(incident) == 0) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "Alert incident changed concurrently; refresh and retry");
        }
        createEvent(incident, null, AlertEventType.CLOSED, previous, incident.getStatus(),
                "MANUAL", String.valueOf(incident.getId()), uniqueKey("close", incident.getId()),
                actionSummary("告警已关闭", request), Collections.<String, Object>emptyMap(), now, false, null);
        return toView(incident, true);
    }

    public AlertIncidentEntity recordCondition(AlertRuleEntity rule, AlertObservation observation) {
        if (rule == null || observation == null || !StringUtils.hasText(observation.getSubjectKey())) {
            return null;
        }
        LocalDateTime observedAt = observation.getObservedAt() == null ? LocalDateTime.now() : observation.getObservedAt();
        if (rule.getActivationAt() != null && observedAt.isBefore(rule.getActivationAt())
                && isEventSource(observation.getSourceType())) {
            return null;
        }
        for (int attempt = 0; attempt < MAX_STATE_RETRIES; attempt++) {
            try {
                if (stateTransactionTemplate == null) {
                    return recordConditionAttempt(rule, observation, observedAt);
                }
                return stateTransactionTemplate.execute(status -> recordConditionAttempt(rule, observation, observedAt));
            } catch (AlertStateConflictException ignored) {
                // A fresh transaction is required so MySQL repeatable-read does not reuse a stale snapshot.
            }
        }
        throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                "Alert incident changed concurrently; retry the signal evaluation");
    }

    private AlertIncidentEntity recordConditionAttempt(AlertRuleEntity rule, AlertObservation observation,
                                                       LocalDateTime observedAt) {
        AlertRuleEntity currentRule = currentEnabledRule(rule);
        if (currentRule == null || !sameEvaluationDefinition(rule, currentRule)) {
            return null;
        }
        rule = currentRule;
        if (rule.getActivationAt() != null && observedAt.isBefore(rule.getActivationAt())
                && isEventSource(observation.getSourceType())) {
            return null;
        }
        if (StringUtils.hasText(observation.getSourceEventKey())
                && sourceEventExists(rule, namespacedSourceKey(rule, observation.getSourceEventKey()))) {
            return findIncident(rule, observation);
        }
        String signature = signature(rule, observation);
        AlertIncidentEntity incident = alertIncidentMapper.selectOne(new LambdaQueryWrapper<AlertIncidentEntity>()
                .eq(AlertIncidentEntity::getTenantId, rule.getTenantId())
                .eq(AlertIncidentEntity::getProjectId, rule.getProjectId())
                .eq(AlertIncidentEntity::getSignature, signature)
                .last("limit 1"));
        if (observation.isActive()) {
            return activate(rule, incident, observation, signature, observedAt);
        }
        return recover(rule, incident, observation, observedAt);
    }

    private AlertRuleEntity currentEnabledRule(AlertRuleEntity snapshot) {
        if (snapshot.getId() == null || !StringUtils.hasText(snapshot.getTenantId()) || snapshot.getProjectId() == null) {
            return null;
        }
        return alertRuleMapper.selectOne(new LambdaQueryWrapper<AlertRuleEntity>()
                .eq(AlertRuleEntity::getId, snapshot.getId())
                .eq(AlertRuleEntity::getTenantId, snapshot.getTenantId())
                .eq(AlertRuleEntity::getProjectId, snapshot.getProjectId())
                .eq(AlertRuleEntity::getEnabled, 1)
                .last("limit 1"));
    }

    private boolean sameEvaluationDefinition(AlertRuleEntity snapshot, AlertRuleEntity current) {
        return Objects.equals(snapshot.getRuleType(), current.getRuleType())
                && Objects.equals(snapshot.getSubjectType(), current.getSubjectType())
                && Objects.equals(snapshot.getSubjectId(), current.getSubjectId())
                && Objects.equals(snapshot.getConditionJson(), current.getConditionJson())
                && Objects.equals(snapshot.getActivationAt(), current.getActivationAt());
    }

    @Transactional
    public AlertEventView testRule(Long ruleId) {
        alertRuleService.requireManage();
        AlertRuleEntity rule = alertRuleService.requireCurrentProjectRule(ruleId);
        LocalDateTime now = LocalDateTime.now();
        AlertObservation observation = new AlertObservation()
                .setActive(true)
                .setSubjectType(rule.getSubjectType())
                .setSubjectKey(rule.getSubjectId() == null ? "TEST" : String.valueOf(rule.getSubjectId()))
                .setSubjectId(rule.getSubjectId())
                .setSubjectName(StringUtils.hasText(rule.getSubjectNameSnapshot()) ? rule.getSubjectNameSnapshot() : "测试对象")
                .setTargetPath(targetPath(rule.getSubjectType(), rule.getSubjectId(), null))
                .setSummary("[测试] 告警规则 " + rule.getName() + " 通知测试")
                .setSourceType("TEST")
                .setSourceId(String.valueOf(rule.getId()))
                .setSourceEventKey(uniqueKey("test-rule", rule.getId()))
                .setObservedAt(now);
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("test", Boolean.TRUE);
        evidence.put("ruleType", rule.getRuleType());
        observation.setEvidence(evidence);
        AlertEventEntity event = createStandaloneEvent(rule, null, observation, AlertEventType.TEST);
        createDeliveries(rule, null, event, alertRuleService.resolveTestOwnerUserId(rule).orElse(null), true);
        return toEventView(event);
    }

    @Transactional
    public AlertEventView testChannel(Long channelId) {
        alertRuleService.requireManage();
        AlertChannelEntity channel = alertChannelMapper.selectOne(new LambdaQueryWrapper<AlertChannelEntity>()
                .eq(AlertChannelEntity::getId, channelId)
                .eq(AlertChannelEntity::getTenantId, securityService.currentTenantId())
                .eq(AlertChannelEntity::getProjectId, projectResourceAccessService.requireCurrentProjectId())
                .last("limit 1"));
        if (channel == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Alert channel was not found");
        }
        if (AlertChannelType.ELINK.name().equals(channel.getChannelType())
                && AlertChannelService.ELINK_RECIPIENT_MODE_RULE_RECIPIENTS.equals(
                elinkRecipientMode(channel))) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "Rule-recipient eLink channels must be tested from an alert rule");
        }
        LocalDateTime now = LocalDateTime.now();
        AlertObservation observation = new AlertObservation()
                .setActive(true)
                .setSubjectType("LOG_STORAGE")
                .setSubjectKey("CHANNEL_TEST:" + channel.getId())
                .setSubjectName(channel.getName())
                .setTargetPath("/alerts?tab=channels")
                .setSummary("[测试] 通知通道 " + channel.getName() + " 连通性测试")
                .setSourceType("TEST")
                .setSourceId(String.valueOf(channel.getId()))
                .setSourceEventKey(uniqueKey("test-channel", channel.getId()))
                .setObservedAt(now);
        AlertEventEntity event = createStandaloneEvent(null, channel, observation, AlertEventType.TEST);
        createChannelDelivery(event, null, channel, payload(null, null, event), false);
        return toEventView(event);
    }

    public AlertSummaryView summary() {
        return summarySupport.summary();
    }

    public PageView<AlertTenantProjectSummaryView> tenantSummary(AlertTenantSummaryQueryRequest request) {
        return summarySupport.tenantSummary(request);
    }

    private AlertIncidentEntity activate(AlertRuleEntity rule, AlertIncidentEntity incident,
                                         AlertObservation observation, String signature, LocalDateTime observedAt) {
        if (incident != null && isOlderObservation(incident, observedAt)) {
            return incident;
        }
        AlertEventType eventType;
        String statusFrom;
        boolean notify;
        if (incident == null) {
            incident = new AlertIncidentEntity();
            incident.setTenantId(rule.getTenantId());
            incident.setProjectId(rule.getProjectId());
            incident.setRuleId(rule.getId());
            incident.setRuleNameSnapshot(rule.getName());
            incident.setRuleType(rule.getRuleType());
            incident.setSignature(signature);
            incident.setSubjectType(observation.getSubjectType());
            incident.setSubjectKey(observation.getSubjectKey());
            incident.setSubjectId(observation.getSubjectId());
            incident.setSubjectNameSnapshot(observation.getSubjectName());
            incident.setTargetPath(observation.getTargetPath());
            incident.setSeverity(rule.getSeverity());
            incident.setStatus(AlertIncidentStatus.OPEN.name());
            incident.setSummary(sanitizeText(observation.getSummary(), 1000));
            incident.setCurrentEvidenceJson(sanitizeEvidence(observation.getEvidence()));
            incident.setOccurrenceCount(1);
            incident.setNotificationCount(0);
            incident.setReopenCount(0);
            incident.setConditionActive(1);
            incident.setClosedWhileActive(0);
            incident.setFirstTriggeredAt(observedAt);
            incident.setLastTriggeredAt(observedAt);
            incident.setVersion(0);
            try {
                alertIncidentMapper.insert(incident);
            } catch (DuplicateKeyException ex) {
                throw AlertStateConflictException.INSTANCE;
            }
            eventType = AlertEventType.TRIGGERED;
            statusFrom = null;
            notify = true;
        } else {
            statusFrom = incident.getStatus();
            incident.setConditionActive(1);
            incident.setLastTriggeredAt(observedAt);
            incident.setSummary(sanitizeText(observation.getSummary(), 1000));
            incident.setCurrentEvidenceJson(sanitizeEvidence(observation.getEvidence()));
            incident.setOccurrenceCount(safe(incident.getOccurrenceCount()) + 1);
            if (AlertIncidentStatus.CLOSED.name().equals(incident.getStatus())
                    && Integer.valueOf(1).equals(incident.getClosedWhileActive())) {
                eventType = AlertEventType.REPEATED;
                notify = false;
            } else if (AlertIncidentStatus.RECOVERED.name().equals(incident.getStatus())
                    || AlertIncidentStatus.CLOSED.name().equals(incident.getStatus())) {
                incident.setStatus(AlertIncidentStatus.OPEN.name());
                incident.setRecoveredAt(null);
                incident.setAcknowledgedAt(null);
                incident.setAcknowledgedBy(null);
                incident.setClosedAt(null);
                incident.setClosedBy(null);
                incident.setClosedWhileActive(0);
                incident.setReopenCount(safe(incident.getReopenCount()) + 1);
                eventType = AlertEventType.REOPENED;
                notify = true;
            } else {
                eventType = AlertEventType.REPEATED;
                notify = silenceExpired(rule, incident, observedAt);
            }
            if (alertIncidentMapper.updateById(incident) == 0) {
                throw AlertStateConflictException.INSTANCE;
            }
        }
        AlertEventEntity event = null;
        boolean quietScheduledRepeat = AlertEventType.REPEATED == eventType && !notify
                && "SCHEDULED_EVALUATION".equalsIgnoreCase(observation.getSourceType());
        if (!quietScheduledRepeat) {
            event = createEvent(incident, rule, eventType, statusFrom, incident.getStatus(),
                    observation.getSourceType(), observation.getSourceId(), sourceKey(rule, observation, eventType),
                    observation.getSummary(), observation.getEvidence(), observedAt, notify, observation.getOwnerUserId());
        }
        if (notify) {
            markNotified(incident, rule, observedAt);
            createDeliveries(rule, incident, event, observation.getOwnerUserId(), false);
        }
        rule.setLastTriggeredAt(observedAt);
        alertRuleMapper.update(null, new LambdaUpdateWrapper<AlertRuleEntity>()
                .eq(AlertRuleEntity::getId, rule.getId())
                .set(AlertRuleEntity::getLastTriggeredAt, observedAt));
        return incident;
    }

    private AlertIncidentEntity recover(AlertRuleEntity rule, AlertIncidentEntity incident,
                                        AlertObservation observation, LocalDateTime observedAt) {
        if (incident == null || !Integer.valueOf(1).equals(incident.getConditionActive())) {
            return incident;
        }
        if (isOlderObservation(incident, observedAt)) {
            return incident;
        }
        String previous = incident.getStatus();
        incident.setConditionActive(0);
        incident.setRecoveredAt(observedAt);
        incident.setCurrentEvidenceJson(sanitizeEvidence(observation.getEvidence()));
        incident.setSummary(sanitizeText(observation.getSummary(), 1000));
        boolean closed = AlertIncidentStatus.CLOSED.name().equals(previous);
        if (closed) {
            incident.setClosedWhileActive(0);
        } else {
            incident.setStatus(AlertIncidentStatus.RECOVERED.name());
        }
        if (alertIncidentMapper.updateById(incident) == 0) {
            throw AlertStateConflictException.INSTANCE;
        }
        boolean notify = !closed && Integer.valueOf(1).equals(rule.getRecoveryNotificationEnabled());
        AlertEventEntity event = createEvent(incident, rule, AlertEventType.RECOVERED, previous, incident.getStatus(),
                observation.getSourceType(), observation.getSourceId(), sourceKey(rule, observation, AlertEventType.RECOVERED),
                observation.getSummary(), observation.getEvidence(), observedAt, notify, observation.getOwnerUserId());
        if (notify) {
            markNotified(incident, rule, observedAt);
            createDeliveries(rule, incident, event, observation.getOwnerUserId(), false);
        }
        return incident;
    }

    private AlertEventEntity createEvent(AlertIncidentEntity incident, AlertRuleEntity rule, AlertEventType eventType,
                                         String statusFrom, String statusTo, String sourceType, String sourceId,
                                         String sourceEventKey, String summary, Map<String, Object> evidence,
                                         LocalDateTime observedAt, boolean notificationEvent, Long ownerUserId) {
        AlertEventEntity event = new AlertEventEntity();
        event.setTenantId(incident.getTenantId());
        event.setProjectId(incident.getProjectId());
        event.setIncidentId(incident.getId());
        event.setRuleId(incident.getRuleId());
        event.setEventType(eventType.name());
        event.setStatusFrom(statusFrom);
        event.setStatusTo(statusTo);
        event.setSourceType(sourceType);
        event.setSourceId(sourceId);
        event.setSourceEventKey(truncate(sourceEventKey, 255));
        event.setSubjectType(incident.getSubjectType());
        event.setSubjectKey(incident.getSubjectKey());
        event.setSubjectId(incident.getSubjectId());
        event.setSubjectNameSnapshot(incident.getSubjectNameSnapshot());
        event.setTargetPath(incident.getTargetPath());
        event.setSeverity(incident.getSeverity());
        event.setSummary(sanitizeText(summary, 1000));
        event.setEvidenceJson(sanitizeEvidence(evidence));
        event.setActorUserId(isManual(eventType) ? securityService.currentUserId() : null);
        event.setActorNameSnapshot(isManual(eventType) ? securityService.currentUsername() : null);
        event.setObservedAt(observedAt);
        try {
            alertEventMapper.insert(event);
        } catch (DuplicateKeyException ex) {
            AlertEventEntity existing = alertEventMapper.selectOne(new LambdaQueryWrapper<AlertEventEntity>()
                    .eq(AlertEventEntity::getTenantId, event.getTenantId())
                    .eq(AlertEventEntity::getProjectId, event.getProjectId())
                    .eq(AlertEventEntity::getSourceEventKey, event.getSourceEventKey())
                    .last("limit 1"));
            return existing == null ? event : existing;
        }
        return event;
    }

    private AlertEventEntity createStandaloneEvent(AlertRuleEntity rule, AlertChannelEntity channel,
                                                   AlertObservation observation, AlertEventType eventType) {
        AlertEventEntity event = new AlertEventEntity();
        event.setTenantId(rule != null ? rule.getTenantId() : channel.getTenantId());
        event.setProjectId(rule != null ? rule.getProjectId() : channel.getProjectId());
        event.setRuleId(rule == null ? null : rule.getId());
        event.setEventType(eventType.name());
        event.setSourceType(observation.getSourceType());
        event.setSourceId(observation.getSourceId());
        event.setSourceEventKey(observation.getSourceEventKey());
        event.setSubjectType(observation.getSubjectType());
        event.setSubjectKey(observation.getSubjectKey());
        event.setSubjectId(observation.getSubjectId());
        event.setSubjectNameSnapshot(observation.getSubjectName());
        event.setTargetPath(observation.getTargetPath());
        event.setSeverity(rule == null ? "INFO" : rule.getSeverity());
        event.setSummary(sanitizeText(observation.getSummary(), 1000));
        event.setEvidenceJson(sanitizeEvidence(observation.getEvidence()));
        event.setActorUserId(securityService.currentUserId());
        event.setActorNameSnapshot(securityService.currentUsername());
        event.setObservedAt(observation.getObservedAt());
        alertEventMapper.insert(event);
        return event;
    }

    private void createDeliveries(AlertRuleEntity rule, AlertIncidentEntity incident, AlertEventEntity event,
                                  Long ownerUserId, boolean test) {
        Map<String, Object> payload = payload(rule, incident, event);
        List<Long> resolvedRecipients = null;
        if (Integer.valueOf(1).equals(rule.getInAppEnabled())) {
            resolvedRecipients = recipientResolver.resolve(rule, ownerUserId);
            for (Long userId : resolvedRecipients) {
                AlertDeliveryEntity delivery = baseDelivery(event, incident, AlertChannelType.IN_APP.name(),
                        "IN_APP:" + userId, payload);
                delivery.setRecipientUserId(userId);
                delivery.setChannelNameSnapshot("站内信");
                setDeliveryRecipientDisplay(delivery, "Studio 用户 " + userId);
                insertDelivery(delivery);
            }
        }
        if (rule.getWebhookChannelIdsJson() != null) {
            for (Long channelId : rule.getWebhookChannelIdsJson()) {
                AlertChannelEntity channel = alertChannelMapper.selectOne(new LambdaQueryWrapper<AlertChannelEntity>()
                        .eq(AlertChannelEntity::getId, channelId)
                        .eq(AlertChannelEntity::getTenantId, rule.getTenantId())
                        .eq(AlertChannelEntity::getProjectId, rule.getProjectId())
                        .last("limit 1"));
                if (channel == null) {
                    continue;
                }
                if (AlertChannelType.ELINK.name().equals(channel.getChannelType())
                        && AlertChannelService.ELINK_RECIPIENT_MODE_RULE_RECIPIENTS.equals(
                        elinkRecipientMode(channel))) {
                    if (resolvedRecipients == null) {
                        resolvedRecipients = recipientResolver.resolve(rule, ownerUserId);
                    }
                    createRuleRecipientElinkDeliveries(event, incident, channel, payload, resolvedRecipients);
                } else {
                    createChannelDelivery(event, incident, channel, payload, true);
                }
            }
        }
    }

    private void createRuleRecipientElinkDeliveries(AlertEventEntity event, AlertIncidentEntity incident,
                                                     AlertChannelEntity channel, Map<String, Object> payload,
                                                     List<Long> recipientUserIds) {
        if (!Integer.valueOf(1).equals(channel.getEnabled())) {
            createChannelDelivery(event, incident, channel, payload, true);
            return;
        }
        if (recipientUserIds == null || recipientUserIds.isEmpty()) {
            AlertDeliveryEntity delivery = dynamicElinkDelivery(event, incident, channel, payload,
                    "NO_RECIPIENT", null);
            markDead(delivery, "The alert rule did not resolve any active recipient");
            insertDelivery(delivery);
            return;
        }
        Map<Long, String> elinkUserIds = recipientResolver.resolveElinkUserIds(recipientUserIds);
        Map<Long, String> mobiles = recipientResolver.resolveStudioUserMobiles(recipientUserIds);
        for (Long studioUserId : recipientUserIds) {
            AlertDeliveryEntity delivery = dynamicElinkDelivery(event, incident, channel, payload,
                    String.valueOf(studioUserId), studioUserId);
            String elinkUserId = elinkUserIds.get(studioUserId);
            String mobile = mobiles.get(studioUserId);
            Map<String, Object> deliveryPayload = new LinkedHashMap<String, Object>(delivery.getPayloadJson());
            if (StringUtils.hasText(elinkUserId)) {
                deliveryPayload.put("_elinkTargetUserId", elinkUserId);
                delivery.setPayloadJson(deliveryPayload);
            } else if (StringUtils.hasText(mobile)) {
                deliveryPayload.put("_elinkTargetMobile", mobile);
                delivery.setPayloadJson(deliveryPayload);
                setDeliveryRecipientDisplay(delivery,
                        "规则接收人（手机号 " + AlertSensitiveTextSanitizer.sanitize(mobile) + "）");
            } else {
                markDead(delivery, "Studio user " + studioUserId
                        + " is not bound to an eLink account and has no valid mobile");
            }
            insertDelivery(delivery);
        }
    }

    private AlertDeliveryEntity dynamicElinkDelivery(AlertEventEntity event, AlertIncidentEntity incident,
                                                      AlertChannelEntity channel, Map<String, Object> payload,
                                                      String recipientKey, Long studioUserId) {
        AlertDeliveryEntity delivery = baseDelivery(event, incident, AlertChannelType.ELINK.name(),
                AlertChannelType.ELINK.name() + ":" + channel.getId() + ":RECIPIENT:" + recipientKey, payload);
        delivery.setChannelId(channel.getId());
        delivery.setChannelNameSnapshot(channel.getName());
        delivery.setRecipientUserId(studioUserId);
        setDeliveryRecipientDisplay(delivery, studioUserId == null
                ? "规则接收人" : "规则接收人（Studio 用户 " + studioUserId + "）");
        return delivery;
    }

    private void markDead(AlertDeliveryEntity delivery, String message) {
        delivery.setStatus(AlertDeliveryStatus.DEAD.name());
        delivery.setNextAttemptAt(null);
        delivery.setErrorMessage(message);
    }

    private String elinkRecipientMode(AlertChannelEntity channel) {
        if (alertChannelService != null) {
            return alertChannelService.elinkRecipientMode(channel);
        }
        Object value = channel == null || channel.getConfigJson() == null
                ? null : channel.getConfigJson().get("recipientMode");
        return value == null ? AlertChannelService.ELINK_RECIPIENT_MODE_FIXED
                : String.valueOf(value).trim().toUpperCase(java.util.Locale.ROOT);
    }

    private AlertDeliveryEntity createChannelDelivery(AlertEventEntity event, AlertIncidentEntity incident,
                                                       AlertChannelEntity channel, Map<String, Object> payload,
                                                       boolean skipDisabledChannel) {
        String channelType = AlertChannelType.ELINK.name().equals(channel.getChannelType())
                ? AlertChannelType.ELINK.name() : AlertChannelType.WEBHOOK.name();
        AlertDeliveryEntity delivery = baseDelivery(event, incident, channelType,
                channelType + ":" + channel.getId(), payload);
        delivery.setChannelId(channel.getId());
        delivery.setChannelNameSnapshot(channel.getName());
        setDeliveryRecipientDisplay(delivery, channelRecipientDisplay(channel, channelType));
        if (skipDisabledChannel && !Integer.valueOf(1).equals(channel.getEnabled())) {
            delivery.setStatus(AlertDeliveryStatus.SKIPPED.name());
            delivery.setNextAttemptAt(null);
            String channelLabel = AlertChannelType.ELINK.name().equals(channelType) ? "eLink" : "Webhook";
            delivery.setErrorMessage(channelLabel + " channel is disabled");
        }
        insertDelivery(delivery);
        return delivery;
    }

    private AlertDeliveryEntity baseDelivery(AlertEventEntity event, AlertIncidentEntity incident,
                                              String channelType, String deliveryKey, Map<String, Object> payload) {
        AlertDeliveryEntity delivery = new AlertDeliveryEntity();
        delivery.setTenantId(event.getTenantId());
        delivery.setProjectId(event.getProjectId());
        delivery.setEventId(event.getId());
        delivery.setIncidentId(incident == null ? null : incident.getId());
        delivery.setDeliveryKey(deliveryKey);
        delivery.setChannelType(channelType);
        delivery.setStatus(AlertDeliveryStatus.PENDING.name());
        delivery.setAttemptCount(0);
        delivery.setNextAttemptAt(LocalDateTime.now());
        delivery.setPayloadJson(AlertDeliveryMessageRenderer.withDeliveryAudit(payload, channelType));
        return delivery;
    }

    private void setDeliveryRecipientDisplay(AlertDeliveryEntity delivery, String recipientDisplay) {
        if (delivery != null) {
            AlertDeliveryMessageRenderer.setRecipientDisplay(delivery.getPayloadJson(), recipientDisplay);
        }
    }

    private String channelRecipientDisplay(AlertChannelEntity channel, String channelType) {
        if (AlertChannelType.WEBHOOK.name().equals(channelType)) {
            return "Webhook 通道：" + fallbackText(channel == null ? null : channel.getName(), "未命名通道");
        }
        if (!AlertChannelType.ELINK.name().equals(channelType) || channel == null) {
            return channel == null ? null : channel.getName();
        }
        if (AlertChannelService.ELINK_RECIPIENT_MODE_RULE_RECIPIENTS.equals(elinkRecipientMode(channel))) {
            return "规则接收人";
        }
        String targetType = alertChannelService == null
                ? channelConfigText(channel, "targetType") : alertChannelService.elinkTargetType(channel);
        if ("GROUP".equalsIgnoreCase(targetType)) {
            String groupName = alertChannelService == null
                    ? channelConfigText(channel, "groupName") : alertChannelService.elinkGroupName(channel);
            Long groupId = alertChannelService == null
                    ? channelConfigLong(channel, "groupId") : alertChannelService.elinkGroupId(channel);
            return "群组：" + fallbackText(groupName, groupId == null ? channel.getName() : String.valueOf(groupId));
        }
        List<String> userNames = alertChannelService == null
                ? channelConfigStrings(channel, "userNames") : alertChannelService.elinkUserNames(channel);
        List<String> userIds = alertChannelService == null
                ? channelConfigStrings(channel, "userIds") : alertChannelService.elinkUserIds(channel);
        List<String> targets = userNames == null || userNames.isEmpty() ? userIds : userNames;
        if (targets != null && !targets.isEmpty()) {
            return "账号：" + String.join("、", targets);
        }
        return "固定通道：" + fallbackText(channel.getName(), "未命名通道");
    }

    private String channelConfigText(AlertChannelEntity channel, String key) {
        Object value = channel == null || channel.getConfigJson() == null ? null : channel.getConfigJson().get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private Long channelConfigLong(AlertChannelEntity channel, String key) {
        String value = channelConfigText(channel, key);
        try {
            return StringUtils.hasText(value) ? Long.valueOf(value) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private List<String> channelConfigStrings(AlertChannelEntity channel, String key) {
        Object value = channel == null || channel.getConfigJson() == null ? null : channel.getConfigJson().get(key);
        List<String> result = new ArrayList<String>();
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                if (StringUtils.hasText(item == null ? null : String.valueOf(item))) {
                    result.add(String.valueOf(item).trim());
                }
            }
        }
        return result;
    }

    private String fallbackText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private void insertDelivery(AlertDeliveryEntity delivery) {
        try {
            alertDeliveryMapper.insert(delivery);
        } catch (DuplicateKeyException ignored) {
            // The event/destination unique key makes outbox generation idempotent.
        }
    }

    private Map<String, Object> payload(AlertRuleEntity rule, AlertIncidentEntity incident, AlertEventEntity event) {
        return presentationSupport.webhookPayload(rule, incident, event);
    }

    private void markNotified(AlertIncidentEntity incident, AlertRuleEntity rule, LocalDateTime at) {
        incident.setLastNotifiedAt(at);
        incident.setNotificationCount(safe(incident.getNotificationCount()) + 1);
        if (alertIncidentMapper.updateById(incident) == 0) {
            throw AlertStateConflictException.INSTANCE;
        }
    }

    private boolean silenceExpired(AlertRuleEntity rule, AlertIncidentEntity incident, LocalDateTime at) {
        if (incident.getLastNotifiedAt() == null) {
            return true;
        }
        int silence = rule.getSilenceMinutes() == null ? 30 : Math.max(0, rule.getSilenceMinutes().intValue());
        return silence == 0 || !at.isBefore(incident.getLastNotifiedAt().plusMinutes(silence));
    }

    private boolean sourceEventExists(AlertRuleEntity rule, String sourceEventKey) {
        Long count = alertEventMapper.selectCount(new LambdaQueryWrapper<AlertEventEntity>()
                .eq(AlertEventEntity::getTenantId, rule.getTenantId())
                .eq(AlertEventEntity::getProjectId, rule.getProjectId())
                .eq(AlertEventEntity::getSourceEventKey, sourceEventKey));
        return count != null && count.longValue() > 0L;
    }

    private AlertIncidentEntity findIncident(AlertRuleEntity rule, AlertObservation observation) {
        return alertIncidentMapper.selectOne(new LambdaQueryWrapper<AlertIncidentEntity>()
                .eq(AlertIncidentEntity::getTenantId, rule.getTenantId())
                .eq(AlertIncidentEntity::getProjectId, rule.getProjectId())
                .eq(AlertIncidentEntity::getSignature, signature(rule, observation))
                .last("limit 1"));
    }

    private String sourceKey(AlertRuleEntity rule, AlertObservation observation, AlertEventType eventType) {
        if (StringUtils.hasText(observation.getSourceEventKey())) {
            return namespacedSourceKey(rule, observation.getSourceEventKey());
        }
        long epochSlot = System.currentTimeMillis() / 30000L;
        return truncate("eval:" + rule.getId() + ":" + observation.getSubjectKey() + ":" + eventType.name() + ":" + epochSlot, 255);
    }

    private String namespacedSourceKey(AlertRuleEntity rule, String sourceEventKey) {
        return truncate("rule:" + rule.getId() + ":" + sourceEventKey, 255);
    }

    private String signature(AlertRuleEntity rule, AlertObservation observation) {
        return sha256(rule.getTenantId() + "|" + rule.getProjectId() + "|" + rule.getId()
                + "|" + observation.getSubjectType() + "|" + observation.getSubjectKey());
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private AlertIncidentEntity requireCurrentProjectIncident(Long id) {
        if (id == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Alert incident id is required");
        }
        AlertIncidentEntity entity = alertIncidentMapper.selectOne(new LambdaQueryWrapper<AlertIncidentEntity>()
                .eq(AlertIncidentEntity::getId, id)
                .eq(AlertIncidentEntity::getTenantId, securityService.currentTenantId())
                .eq(AlertIncidentEntity::getProjectId, projectResourceAccessService.requireCurrentProjectId())
                .last("limit 1"));
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Alert incident was not found");
        }
        return entity;
    }

    private AlertIncidentView toView(AlertIncidentEntity entity, boolean detail) {
        return presentationSupport.toView(entity, detail);
    }

    public AlertEventView toEventView(AlertEventEntity entity) {
        return presentationSupport.toEventView(entity);
    }

    public AlertDeliveryView toDeliveryView(AlertDeliveryEntity entity) {
        return presentationSupport.toDeliveryView(entity);
    }

    private Map<String, Object> sanitizeEvidence(Map<String, Object> evidence) {
        return presentationSupport.sanitizeEvidence(evidence);
    }

    private String sanitizeText(String value, int max) {
        return presentationSupport.sanitizeText(value, max);
    }

    private boolean isOlderObservation(AlertIncidentEntity incident, LocalDateTime observedAt) {
        LocalDateTime latest = incident.getLastTriggeredAt();
        if (incident.getRecoveredAt() != null && (latest == null || incident.getRecoveredAt().isAfter(latest))) {
            latest = incident.getRecoveredAt();
        }
        return latest != null && observedAt != null && observedAt.isBefore(latest);
    }

    private String actionSummary(String fallback, AlertIncidentActionRequest request) {
        return request != null && StringUtils.hasText(request.getComment())
                ? fallback + "：" + presentationSupport.sanitizeText(request.getComment().trim(), 500) : fallback;
    }

    private boolean isManual(AlertEventType eventType) {
        return Arrays.asList(AlertEventType.ACKNOWLEDGED, AlertEventType.CLOSED, AlertEventType.TEST).contains(eventType);
    }

    private boolean isEventSource(String sourceType) {
        return StringUtils.hasText(sourceType) && !"SCHEDULED_EVALUATION".equalsIgnoreCase(sourceType);
    }

    private int safe(Integer value) {
        return value == null ? 0 : value.intValue();
    }

    private int pageNo(Integer value) {
        return value == null || value.intValue() < 1 ? 1 : value.intValue();
    }

    private int pageSize(Integer value) {
        return Math.min(value == null || value.intValue() < 1 ? 20 : value.intValue(), 100);
    }

    private String upper(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private String uniqueKey(String prefix, Long id) {
        return truncate(prefix + ":" + id + ":" + UUID.randomUUID(), 255);
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public static String targetPath(String subjectType, Long subjectId, Long sourceId) {
        return AlertIncidentPresentationSupport.targetPath(subjectType, subjectId, sourceId);
    }

    private static final class AlertStateConflictException extends RuntimeException {
        private static final AlertStateConflictException INSTANCE = new AlertStateConflictException();

        private AlertStateConflictException() {
            super(null, null, false, false);
        }
    }
}
