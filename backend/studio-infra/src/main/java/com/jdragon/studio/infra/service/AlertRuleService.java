package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.AlertDeliveryStatus;
import com.jdragon.studio.dto.enums.AlertIncidentStatus;
import com.jdragon.studio.dto.enums.AlertSeverity;
import com.jdragon.studio.dto.enums.AlertSubjectType;
import com.jdragon.studio.dto.model.AlertOptionsView;
import com.jdragon.studio.dto.model.AlertRuleView;
import com.jdragon.studio.dto.model.AlertSelectOptionView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.AlertRuleQueryRequest;
import com.jdragon.studio.dto.model.request.AlertRuleSaveRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.AlertIncidentEntity;
import com.jdragon.studio.infra.entity.AlertRuleEntity;
import com.jdragon.studio.infra.mapper.AlertChannelMapper;
import com.jdragon.studio.infra.mapper.AlertIncidentMapper;
import com.jdragon.studio.infra.mapper.AlertRuleMapper;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.ProjectWorkerBindingMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.QualityTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.model.AlertSignal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class AlertRuleService {

    private static final int MAX_NAME_LENGTH = 255;

    private final AlertRuleMapper alertRuleMapper;
    private final AlertIncidentMapper alertIncidentMapper;
    private final AlertRuleDefinitionRegistry definitionRegistry;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final AlertRuleTargetService targetService;
    private AlertSignalPublisher alertSignalPublisher;
    private StudioPlatformProperties properties;

    public AlertRuleService(AlertRuleMapper alertRuleMapper,
                            AlertChannelMapper alertChannelMapper,
                            AlertIncidentMapper alertIncidentMapper,
                            AlertRuleDefinitionRegistry definitionRegistry,
                            StudioSecurityService securityService,
                            ProjectResourceAccessService projectResourceAccessService,
                            CollectionTaskDefinitionMapper collectionTaskDefinitionMapper,
                            QualityTaskDefinitionMapper qualityTaskDefinitionMapper,
                            WorkflowDefinitionMapper workflowDefinitionMapper,
                            DataServiceDefinitionMapper dataServiceDefinitionMapper,
                            DataIngestionServiceMapper dataIngestionServiceMapper,
                            ProtocolConversionServiceMapper protocolConversionServiceMapper,
                            ProjectWorkerBindingMapper projectWorkerBindingMapper,
                            ProjectMemberMapper projectMemberMapper,
                            StudioUserMapper studioUserMapper) {
        this.alertRuleMapper = alertRuleMapper;
        this.alertIncidentMapper = alertIncidentMapper;
        this.definitionRegistry = definitionRegistry;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.targetService = new AlertRuleTargetService(alertChannelMapper, definitionRegistry, securityService,
                projectResourceAccessService, collectionTaskDefinitionMapper, qualityTaskDefinitionMapper,
                workflowDefinitionMapper, dataServiceDefinitionMapper, dataIngestionServiceMapper,
                protocolConversionServiceMapper, projectWorkerBindingMapper, projectMemberMapper, studioUserMapper);
    }

    @Autowired
    void setAlertSignalPublisher(AlertSignalPublisher alertSignalPublisher) {
        this.alertSignalPublisher = alertSignalPublisher;
    }

    @Autowired
    void setStudioPlatformProperties(StudioPlatformProperties properties) {
        this.properties = properties;
    }

    public AlertOptionsView options() {
        AlertOptionsView view = new AlertOptionsView();
        view.setRuleTypes(definitionRegistry.options());
        for (AlertSeverity value : AlertSeverity.values()) {
            view.getSeverities().add(value.name());
        }
        for (AlertIncidentStatus value : AlertIncidentStatus.values()) {
            view.getIncidentStatuses().add(value.name());
        }
        for (AlertDeliveryStatus value : AlertDeliveryStatus.values()) {
            view.getDeliveryStatuses().add(value.name());
        }
        view.setElinkChannelEnabled(elinkChannelEnabled());
        view.setCanManage(canManage());
        view.setCanHandleIncidents(projectResourceAccessService.hasProjectContext());
        view.setCanViewTenantSummary(securityService.hasAnyRole(
                StudioConstants.ROLE_SUPER_ADMIN, StudioConstants.ROLE_TENANT_ADMIN));
        return view;
    }

    private boolean elinkChannelEnabled() {
        return properties == null || properties.getAlert() == null || properties.getAlert().getElink() == null
                || properties.getAlert().getElink().isEnabled();
    }

    public PageView<AlertRuleView> query(AlertRuleQueryRequest request) {
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        int pageNo = pageNo(request == null ? null : request.getPageNo());
        int pageSize = pageSize(request == null ? null : request.getPageSize());
        LambdaQueryWrapper<AlertRuleEntity> query = new LambdaQueryWrapper<AlertRuleEntity>()
                .eq(AlertRuleEntity::getTenantId, securityService.currentTenantId())
                .eq(AlertRuleEntity::getProjectId, projectId)
                .like(request != null && StringUtils.hasText(request.getKeyword()), AlertRuleEntity::getName, trim(request == null ? null : request.getKeyword()))
                .eq(request != null && StringUtils.hasText(request.getRuleType()), AlertRuleEntity::getRuleType, upper(request == null ? null : request.getRuleType()))
                .eq(request != null && StringUtils.hasText(request.getSubjectType()), AlertRuleEntity::getSubjectType, upper(request == null ? null : request.getSubjectType()))
                .eq(request != null && StringUtils.hasText(request.getSeverity()), AlertRuleEntity::getSeverity, upper(request == null ? null : request.getSeverity()))
                .eq(request != null && request.getEnabled() != null, AlertRuleEntity::getEnabled,
                        request != null && Boolean.TRUE.equals(request.getEnabled()) ? 1 : 0);
        Long total = alertRuleMapper.selectCount(query);
        List<AlertRuleEntity> entities = alertRuleMapper.selectList(query
                .orderByDesc(AlertRuleEntity::getEnabled)
                .orderByDesc(AlertRuleEntity::getUpdatedAt)
                .orderByDesc(AlertRuleEntity::getId)
                .last("limit " + ((pageNo - 1) * pageSize) + "," + pageSize));
        List<AlertRuleView> items = new ArrayList<AlertRuleView>();
        for (AlertRuleEntity entity : entities) {
            items.add(toView(entity));
        }
        return PageView.of(pageNo, pageSize, total == null ? 0L : total.longValue(), items);
    }

    public AlertRuleView get(Long id) {
        return toView(requireCurrentProjectRule(id));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public AlertRuleView save(AlertRuleSaveRequest request) {
        requireManage();
        if (request == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Alert rule payload is required");
        }
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        String tenantId = securityService.currentTenantId();
        String name = requireText(request.getName(), "Alert rule name is required");
        if (name.length() > MAX_NAME_LENGTH) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Alert rule name is too long");
        }
        String ruleType = definitionRegistry.parseRuleType(request.getRuleType()).name();
        String subjectType = definitionRegistry.parseSubjectType(request.getSubjectType()).name();
        String severity = parseSeverity(request.getSeverity());
        if (request.getSubjectId() != null
                && (AlertSubjectType.PROJECT_QUEUE.name().equals(subjectType) || AlertSubjectType.LOG_STORAGE.name().equals(subjectType))) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Project queue and log storage rules do not accept a subject id");
        }
        AlertRuleTargetService.SubjectDescriptor subject = request.getSubjectId() == null
                ? null
                : targetService.requireSubject(subjectType, request.getSubjectId(), projectId);
        assertUniqueName(name, request.getId(), tenantId, projectId);
        List<Long> recipientUserIds = normalizeIds(request.getRecipientUserIds());
        targetService.validateProjectMembers(recipientUserIds, tenantId, projectId);
        List<Long> channelIds = normalizeIds(request.getWebhookChannelIds());
        targetService.validateChannels(channelIds, tenantId, projectId);
        boolean inAppEnabled = request.getInAppEnabled() == null || Boolean.TRUE.equals(request.getInAppEnabled());
        boolean notifyOwner = Boolean.TRUE.equals(request.getNotifyResourceOwner());
        boolean notifyAdmins = request.getNotifyProjectAdmins() == null || Boolean.TRUE.equals(request.getNotifyProjectAdmins());
        if (notifyOwner && !definitionRegistry.supportsResourceOwner(subjectType)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Resource-owner notification is not supported for " + subjectType);
        }
        boolean hasInAppDestination = inAppEnabled && targetService.hasEffectiveInAppDestination(subjectType, request.getSubjectId(),
                recipientUserIds, notifyOwner, notifyAdmins, tenantId, projectId);
        if (!hasInAppDestination && !targetService.hasEnabledWebhook(channelIds, tenantId, projectId)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "At least one in-app recipient source or notification channel is required");
        }
        AlertRuleEntity entity = request.getId() == null ? new AlertRuleEntity() : requireCurrentProjectRule(request.getId());
        rejectActiveIncidentRetargeting(entity, ruleType, subjectType, request.getSubjectId());
        LocalDateTime now = LocalDateTime.now();
        boolean wasEnabled = Integer.valueOf(1).equals(entity.getEnabled());
        boolean enabled = request.getEnabled() == null
                ? wasEnabled : Boolean.TRUE.equals(request.getEnabled());
        entity.setTenantId(tenantId);
        entity.setProjectId(projectId);
        entity.setName(name);
        entity.setDescription(trimToNull(request.getDescription()));
        entity.setRuleType(ruleType);
        entity.setSubjectType(subjectType);
        entity.setSubjectId(request.getSubjectId());
        entity.setSubjectNameSnapshot(subject == null ? null : subject.name());
        entity.setSeverity(severity);
        entity.setEnabled(enabled ? 1 : 0);
        entity.setConditionJson(definitionRegistry.validateAndNormalize(ruleType, subjectType, request.getCondition()));
        entity.setSilenceMinutes(bounded(request.getSilenceMinutes(), 30, 0, 10080, "silenceMinutes"));
        entity.setRecoveryNotificationEnabled(request.getRecoveryNotificationEnabled() == null || Boolean.TRUE.equals(request.getRecoveryNotificationEnabled()) ? 1 : 0);
        entity.setInAppEnabled(inAppEnabled ? 1 : 0);
        entity.setRecipientUserIdsJson(recipientUserIds);
        entity.setNotifyResourceOwner(notifyOwner ? 1 : 0);
        entity.setNotifyProjectAdmins(notifyAdmins ? 1 : 0);
        entity.setWebhookChannelIdsJson(channelIds);
        entity.setUpdatedBy(securityService.currentUserId());
        if (entity.getId() == null) {
            entity.setCreatedBy(securityService.currentUserId());
        }
        if (enabled && !wasEnabled) {
            entity.setActivationAt(now);
            entity.setLastEvaluationError(null);
        }
        try {
            if (entity.getId() == null) {
                alertRuleMapper.insert(entity);
            } else {
                alertRuleMapper.updateById(entity);
                if (enabled && !wasEnabled) {
                    clearEvaluationError(entity.getId());
                }
            }
        } catch (DuplicateKeyException ex) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR, "告警规则名称已存在");
        }
        if (enabled && !wasEnabled) {
            publishActivationSignal(entity);
        }
        return toView(entity);
    }

    @Transactional
    public AlertRuleView enable(Long id) {
        requireManage();
        AlertRuleEntity entity = requireCurrentProjectRule(id);
        if (Integer.valueOf(1).equals(entity.getEnabled())) {
            return toView(entity);
        }
        if (entity.getSubjectId() != null) {
            targetService.requireSubject(entity.getSubjectType(), entity.getSubjectId(), entity.getProjectId());
        }
        targetService.validateEffectiveDestinations(entity);
        entity.setEnabled(1);
        entity.setActivationAt(LocalDateTime.now());
        entity.setLastEvaluationError(null);
        entity.setUpdatedBy(securityService.currentUserId());
        alertRuleMapper.updateById(entity);
        clearEvaluationError(entity.getId());
        publishActivationSignal(entity);
        return toView(entity);
    }

    @Transactional
    public AlertRuleView disable(Long id) {
        requireManage();
        AlertRuleEntity entity = requireCurrentProjectRule(id);
        entity.setEnabled(0);
        entity.setUpdatedBy(securityService.currentUserId());
        alertRuleMapper.updateById(entity);
        return toView(entity);
    }

    @Transactional
    public void delete(Long id) {
        requireManage();
        AlertRuleEntity entity = requireCurrentProjectRule(id);
        Long activeCount = alertIncidentMapper.selectCount(new LambdaQueryWrapper<AlertIncidentEntity>()
                .eq(AlertIncidentEntity::getTenantId, entity.getTenantId())
                .eq(AlertIncidentEntity::getProjectId, entity.getProjectId())
                .eq(AlertIncidentEntity::getRuleId, entity.getId())
                .in(AlertIncidentEntity::getStatus, AlertIncidentStatus.OPEN.name(), AlertIncidentStatus.ACKNOWLEDGED.name()));
        if (activeCount != null && activeCount.longValue() > 0L) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR, "Close active alert incidents before deleting this rule");
        }
        alertRuleMapper.deleteById(entity.getId());
    }

    public PageView<AlertSelectOptionView> subjectOptions(String subjectTypeValue, String keyword,
                                                          Integer pageNoValue, Integer pageSizeValue) {
        return targetService.subjectOptions(subjectTypeValue, keyword, pageNoValue, pageSizeValue);
    }

    public PageView<AlertSelectOptionView> recipientOptions(String keyword, Integer pageNoValue, Integer pageSizeValue) {
        return targetService.recipientOptions(keyword, pageNoValue, pageSizeValue);
    }

    public List<AlertRuleEntity> enabledRules(String tenantId, Long projectId, String ruleType, String subjectType, Long subjectId) {
        return alertRuleMapper.selectList(new LambdaQueryWrapper<AlertRuleEntity>()
                .eq(AlertRuleEntity::getTenantId, tenantId)
                .eq(AlertRuleEntity::getProjectId, projectId)
                .eq(AlertRuleEntity::getEnabled, 1)
                .eq(StringUtils.hasText(ruleType), AlertRuleEntity::getRuleType, ruleType)
                .eq(StringUtils.hasText(subjectType), AlertRuleEntity::getSubjectType, subjectType)
                .and(subjectId != null, wrapper -> wrapper.isNull(AlertRuleEntity::getSubjectId)
                        .or().eq(AlertRuleEntity::getSubjectId, subjectId)));
    }

    public List<AlertRuleEntity> enabledRulesForEvaluation() {
        return alertRuleMapper.selectList(new LambdaQueryWrapper<AlertRuleEntity>()
                .eq(AlertRuleEntity::getEnabled, 1)
                .orderByAsc(AlertRuleEntity::getId));
    }

    public void markEvaluation(AlertRuleEntity rule, String status, String error) {
        if (rule == null || rule.getId() == null) {
            return;
        }
        LocalDateTime evaluatedAt = LocalDateTime.now();
        String sanitizedError = sanitizeEvaluationError(error);
        rule.setLastEvaluatedAt(evaluatedAt);
        rule.setLastEvaluationStatus(status);
        rule.setLastEvaluationError(sanitizedError);
        alertRuleMapper.update(null, new LambdaUpdateWrapper<AlertRuleEntity>()
                .eq(AlertRuleEntity::getId, rule.getId())
                .eq(AlertRuleEntity::getEnabled, 1)
                .eq(rule.getUpdatedAt() != null, AlertRuleEntity::getUpdatedAt, rule.getUpdatedAt())
                .set(AlertRuleEntity::getLastEvaluatedAt, evaluatedAt)
                .set(AlertRuleEntity::getLastEvaluationStatus, status)
                .set(AlertRuleEntity::getLastEvaluationError, sanitizedError));
    }

    private void publishActivationSignal(AlertRuleEntity rule) {
        if (alertSignalPublisher == null || rule == null || rule.getId() == null) {
            return;
        }
        alertSignalPublisher.publish(new AlertSignal()
                .setTenantId(rule.getTenantId())
                .setProjectId(rule.getProjectId())
                .setSignalType("RULE_ACTIVATED")
                .setSubjectType(rule.getSubjectType())
                .setSubjectId(rule.getSubjectId())
                .setSubjectKey(String.valueOf(rule.getId()))
                .setStatus(rule.getRuleType())
                .setSourceId(String.valueOf(rule.getId()))
                .setSourceEventKey("alert-rule-activated:" + rule.getId() + ":" + rule.getActivationAt())
                .setOccurredAt(rule.getActivationAt()));
    }

    private void clearEvaluationError(Long id) {
        alertRuleMapper.update(null, new LambdaUpdateWrapper<AlertRuleEntity>()
                .eq(AlertRuleEntity::getId, id)
                .set(AlertRuleEntity::getLastEvaluationError, null));
    }

    private String sanitizeEvaluationError(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String sanitized = NotificationTextSanitizer.sanitize(value);
        sanitized = AlertSensitiveTextSanitizer.sanitize(sanitized);
        return truncate(sanitized, 1000);
    }

    public boolean canManage() {
        return securityService.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN,
                StudioConstants.ROLE_TENANT_ADMIN, StudioConstants.ROLE_PROJECT_ADMIN);
    }

    public void requireManage() {
        if (!canManage()) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Alert rule administration permission is required");
        }
    }

    boolean hasEffectiveInAppDestination(AlertRuleEntity rule) {
        return targetService.hasEffectiveInAppDestination(rule);
    }

    public Optional<Long> resolveTestOwnerUserId(AlertRuleEntity rule) {
        return targetService.resolveTestOwnerUserId(rule);
    }

    public AlertRuleEntity requireCurrentProjectRule(Long id) {
        if (id == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Alert rule id is required");
        }
        AlertRuleEntity entity = alertRuleMapper.selectOne(new LambdaQueryWrapper<AlertRuleEntity>()
                .eq(AlertRuleEntity::getId, id)
                .eq(AlertRuleEntity::getTenantId, securityService.currentTenantId())
                .eq(AlertRuleEntity::getProjectId, projectResourceAccessService.requireCurrentProjectId())
                .last("limit 1"));
        if (entity == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Alert rule was not found");
        }
        return entity;
    }

    public AlertRuleView toView(AlertRuleEntity entity) {
        AlertRuleView view = new AlertRuleView();
        view.setId(entity.getId());
        view.setName(entity.getName());
        view.setDescription(entity.getDescription());
        view.setRuleType(entity.getRuleType());
        view.setSubjectType(entity.getSubjectType());
        view.setSubjectId(entity.getSubjectId());
        view.setSubjectName(entity.getSubjectNameSnapshot());
        view.setSeverity(entity.getSeverity());
        view.setEnabled(Integer.valueOf(1).equals(entity.getEnabled()));
        view.setCondition(entity.getConditionJson());
        view.setSilenceMinutes(entity.getSilenceMinutes());
        view.setRecoveryNotificationEnabled(Integer.valueOf(1).equals(entity.getRecoveryNotificationEnabled()));
        view.setInAppEnabled(Integer.valueOf(1).equals(entity.getInAppEnabled()));
        view.setRecipientUserIds(entity.getRecipientUserIdsJson());
        view.setNotifyResourceOwner(Integer.valueOf(1).equals(entity.getNotifyResourceOwner()));
        view.setNotifyProjectAdmins(Integer.valueOf(1).equals(entity.getNotifyProjectAdmins()));
        view.setWebhookChannelIds(entity.getWebhookChannelIdsJson());
        view.setActivationAt(entity.getActivationAt());
        view.setLastEvaluatedAt(entity.getLastEvaluatedAt());
        view.setLastEvaluationStatus(entity.getLastEvaluationStatus());
        view.setLastEvaluationError(entity.getLastEvaluationError());
        view.setLastTriggeredAt(entity.getLastTriggeredAt());
        view.setCreatedBy(entity.getCreatedBy());
        view.setUpdatedBy(entity.getUpdatedBy());
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        return view;
    }

    private void assertUniqueName(String name, Long excludedId, String tenantId, Long projectId) {
        LambdaQueryWrapper<AlertRuleEntity> query = new LambdaQueryWrapper<AlertRuleEntity>()
                .eq(AlertRuleEntity::getTenantId, tenantId)
                .eq(AlertRuleEntity::getProjectId, projectId)
                .eq(AlertRuleEntity::getName, name)
                .ne(excludedId != null, AlertRuleEntity::getId, excludedId);
        Long count = alertRuleMapper.selectCount(query);
        if (count != null && count.longValue() > 0L) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR, "告警规则名称已存在");
        }
    }

    private void rejectActiveIncidentRetargeting(AlertRuleEntity entity, String ruleType,
                                                  String subjectType, Long subjectId) {
        if (entity == null || entity.getId() == null
                || (Objects.equals(entity.getRuleType(), ruleType)
                && Objects.equals(entity.getSubjectType(), subjectType)
                && Objects.equals(entity.getSubjectId(), subjectId))) {
            return;
        }
        Long activeCount = alertIncidentMapper.selectCount(new LambdaQueryWrapper<AlertIncidentEntity>()
                .eq(AlertIncidentEntity::getTenantId, entity.getTenantId())
                .eq(AlertIncidentEntity::getProjectId, entity.getProjectId())
                .eq(AlertIncidentEntity::getRuleId, entity.getId())
                .eq(AlertIncidentEntity::getConditionActive, 1));
        if (activeCount != null && activeCount.longValue() > 0L) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "Close active alert incidents before changing the rule type or target");
        }
    }

    private List<Long> normalizeIds(List<Long> values) {
        Set<Long> unique = new LinkedHashSet<Long>();
        if (values != null) {
            for (Long value : values) {
                if (value != null) {
                    unique.add(value);
                }
            }
        }
        return new ArrayList<Long>(unique);
    }

    private int pageNo(Integer value) {
        return value == null || value.intValue() < 1 ? 1 : value.intValue();
    }

    private int pageSize(Integer value) {
        int resolved = value == null || value.intValue() < 1 ? 20 : value.intValue();
        return Math.min(resolved, 100);
    }

    private int bounded(Integer value, int defaultValue, int min, int max, String name) {
        int resolved = value == null ? defaultValue : value.intValue();
        if (resolved < min || resolved > max) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, name + " must be between " + min + " and " + max);
        }
        return resolved;
    }

    private String parseSeverity(String value) {
        try {
            return AlertSeverity.valueOf(upper(value)).name();
        } catch (Exception ex) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported alert severity: " + value);
        }
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

}
