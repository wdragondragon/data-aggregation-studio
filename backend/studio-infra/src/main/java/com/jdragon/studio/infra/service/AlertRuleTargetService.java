package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.AlertSubjectType;
import com.jdragon.studio.dto.model.AlertSelectOptionView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.infra.entity.AlertChannelEntity;
import com.jdragon.studio.infra.entity.AlertRuleEntity;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.DataIngestionServiceEntity;
import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import com.jdragon.studio.infra.entity.ProjectWorkerBindingEntity;
import com.jdragon.studio.infra.entity.ProtocolConversionServiceEntity;
import com.jdragon.studio.infra.entity.QualityTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.mapper.AlertChannelMapper;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.ProjectWorkerBindingMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.QualityTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class AlertRuleTargetService {

    private final AlertChannelMapper alertChannelMapper;
    private final AlertRuleDefinitionRegistry definitionRegistry;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;
    private final CollectionTaskDefinitionMapper collectionTaskDefinitionMapper;
    private final QualityTaskDefinitionMapper qualityTaskDefinitionMapper;
    private final WorkflowDefinitionMapper workflowDefinitionMapper;
    private final DataServiceDefinitionMapper dataServiceDefinitionMapper;
    private final DataIngestionServiceMapper dataIngestionServiceMapper;
    private final ProtocolConversionServiceMapper protocolConversionServiceMapper;
    private final ProjectWorkerBindingMapper projectWorkerBindingMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final StudioUserMapper studioUserMapper;

    AlertRuleTargetService(AlertChannelMapper alertChannelMapper,
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
        this.alertChannelMapper = alertChannelMapper;
        this.definitionRegistry = definitionRegistry;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
        this.collectionTaskDefinitionMapper = collectionTaskDefinitionMapper;
        this.qualityTaskDefinitionMapper = qualityTaskDefinitionMapper;
        this.workflowDefinitionMapper = workflowDefinitionMapper;
        this.dataServiceDefinitionMapper = dataServiceDefinitionMapper;
        this.dataIngestionServiceMapper = dataIngestionServiceMapper;
        this.protocolConversionServiceMapper = protocolConversionServiceMapper;
        this.projectWorkerBindingMapper = projectWorkerBindingMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.studioUserMapper = studioUserMapper;
    }

    PageView<AlertSelectOptionView> subjectOptions(String subjectTypeValue, String keyword,
                                                    Integer pageNoValue, Integer pageSizeValue) {
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        AlertSubjectType subjectType = definitionRegistry.parseSubjectType(subjectTypeValue);
        int pageNo = pageNo(pageNoValue);
        int pageSize = pageSize(pageSizeValue);
        List<SubjectDescriptor> all = listSubjects(subjectType, projectId, trimToNull(keyword));
        int from = Math.min((pageNo - 1) * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        List<AlertSelectOptionView> items = new ArrayList<AlertSelectOptionView>();
        for (SubjectDescriptor descriptor : all.subList(from, to)) {
            AlertSelectOptionView item = new AlertSelectOptionView();
            item.setId(descriptor.id);
            item.setCode(descriptor.code);
            item.setName(descriptor.name);
            item.setDescription(descriptor.description);
            items.add(item);
        }
        return PageView.of(pageNo, pageSize, all.size(), items);
    }

    PageView<AlertSelectOptionView> recipientOptions(String keyword, Integer pageNoValue, Integer pageSizeValue) {
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        String tenantId = securityService.currentTenantId();
        List<ProjectMemberEntity> members = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getTenantId, tenantId)
                .eq(ProjectMemberEntity::getProjectId, projectId)
                .eq(ProjectMemberEntity::getStatus, StudioConstants.MEMBER_STATUS_ACTIVE));
        Set<Long> ids = new LinkedHashSet<Long>();
        for (ProjectMemberEntity member : members) {
            if (member.getUserId() != null) {
                ids.add(member.getUserId());
            }
        }
        List<StudioUserEntity> users = ids.isEmpty() ? Collections.<StudioUserEntity>emptyList() : studioUserMapper.selectByIds(ids);
        List<AlertSelectOptionView> filtered = new ArrayList<AlertSelectOptionView>();
        String normalizedKeyword = trimToNull(keyword);
        for (StudioUserEntity user : users) {
            String name = StringUtils.hasText(user.getDisplayName()) ? user.getDisplayName() : user.getUsername();
            if (!Objects.equals(tenantId, user.getTenantId()) || !Integer.valueOf(1).equals(user.getEnabled())
                    || (normalizedKeyword != null && !containsIgnoreCase(name, normalizedKeyword)
                    && !containsIgnoreCase(user.getUsername(), normalizedKeyword))) {
                continue;
            }
            AlertSelectOptionView item = new AlertSelectOptionView();
            item.setId(user.getId());
            item.setCode(user.getUsername());
            item.setName(name);
            filtered.add(item);
        }
        filtered.sort((left, right) -> String.valueOf(left.getName()).compareToIgnoreCase(String.valueOf(right.getName())));
        int pageNo = pageNo(pageNoValue);
        int pageSize = pageSize(pageSizeValue);
        int from = Math.min((pageNo - 1) * pageSize, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());
        return PageView.of(pageNo, pageSize, filtered.size(),
                new ArrayList<AlertSelectOptionView>(filtered.subList(from, to)));
    }

    void validateProjectMembers(List<Long> userIds, String tenantId, Long projectId) {
        for (Long userId : userIds) {
            Long count = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMemberEntity>()
                    .eq(ProjectMemberEntity::getTenantId, tenantId)
                    .eq(ProjectMemberEntity::getProjectId, projectId)
                    .eq(ProjectMemberEntity::getUserId, userId)
                    .eq(ProjectMemberEntity::getStatus, StudioConstants.MEMBER_STATUS_ACTIVE));
            if (count == null || count.longValue() == 0L) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "Alert recipient is not an active project member: " + userId);
            }
        }
        Set<Long> enabledUserIds = enabledUserIds(userIds, tenantId);
        for (Long userId : userIds) {
            if (!enabledUserIds.contains(userId)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "Alert recipient account is disabled or missing: " + userId);
            }
        }
    }

    void validateChannels(List<Long> channelIds, String tenantId, Long projectId) {
        for (Long channelId : channelIds) {
            Long count = alertChannelMapper.selectCount(new LambdaQueryWrapper<AlertChannelEntity>()
                    .eq(AlertChannelEntity::getId, channelId)
                    .eq(AlertChannelEntity::getTenantId, tenantId)
                    .eq(AlertChannelEntity::getProjectId, projectId));
            if (count == null || count.longValue() == 0L) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Webhook channel was not found: " + channelId);
            }
        }
    }

    void validateEffectiveDestinations(AlertRuleEntity rule) {
        List<Long> channelIds = normalizeIds(rule.getWebhookChannelIdsJson());
        if (!hasEffectiveInAppDestination(rule)
                && !hasEnabledWebhook(channelIds, rule.getTenantId(), rule.getProjectId())) {
            throw new StudioException(StudioErrorCode.BUSINESS_ERROR,
                    "At least one active in-app recipient or enabled webhook channel is required before enabling this rule");
        }
    }

    boolean hasEnabledWebhook(List<Long> channelIds, String tenantId, Long projectId) {
        if (channelIds == null || channelIds.isEmpty()) {
            return false;
        }
        Long count = alertChannelMapper.selectCount(new LambdaQueryWrapper<AlertChannelEntity>()
                .eq(AlertChannelEntity::getTenantId, tenantId)
                .eq(AlertChannelEntity::getProjectId, projectId)
                .eq(AlertChannelEntity::getEnabled, 1)
                .in(AlertChannelEntity::getId, channelIds));
        return count != null && count.longValue() > 0L;
    }

    boolean hasEffectiveInAppDestination(AlertRuleEntity rule) {
        return rule != null && Integer.valueOf(1).equals(rule.getInAppEnabled())
                && hasEffectiveInAppDestination(rule.getSubjectType(), rule.getSubjectId(), rule.getRecipientUserIdsJson(),
                Integer.valueOf(1).equals(rule.getNotifyResourceOwner()),
                Integer.valueOf(1).equals(rule.getNotifyProjectAdmins()), rule.getTenantId(), rule.getProjectId());
    }

    boolean hasEffectiveInAppDestination(String subjectType, Long subjectId, List<Long> recipientUserIds,
                                          boolean notifyOwner, boolean notifyAdmins,
                                          String tenantId, Long projectId) {
        List<ProjectMemberEntity> members = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getTenantId, tenantId)
                .eq(ProjectMemberEntity::getProjectId, projectId)
                .eq(ProjectMemberEntity::getStatus, StudioConstants.MEMBER_STATUS_ACTIVE));
        Set<Long> activeUserIds = new LinkedHashSet<Long>();
        for (ProjectMemberEntity member : members) {
            if (member.getUserId() != null) {
                activeUserIds.add(member.getUserId());
            }
        }
        Set<Long> enabledActiveUserIds = enabledUserIds(activeUserIds, tenantId);
        if (notifyAdmins) {
            for (ProjectMemberEntity member : members) {
                if (StudioConstants.ROLE_PROJECT_ADMIN.equalsIgnoreCase(member.getRoleCode())
                        && enabledActiveUserIds.contains(member.getUserId())) {
                    return true;
                }
            }
        }
        if (recipientUserIds != null) {
            for (Long userId : recipientUserIds) {
                if (enabledActiveUserIds.contains(userId)) {
                    return true;
                }
            }
        }
        if (!notifyOwner || !definitionRegistry.supportsResourceOwner(subjectType)) {
            return false;
        }
        for (SubjectDescriptor descriptor : listSubjects(definitionRegistry.parseSubjectType(subjectType), projectId, null)) {
            if ((subjectId == null || subjectId.equals(descriptor.id))
                    && enabledActiveUserIds.contains(descriptor.ownerUserId)) {
                return true;
            }
        }
        return false;
    }

    Optional<Long> resolveTestOwnerUserId(AlertRuleEntity rule) {
        if (rule == null || !Integer.valueOf(1).equals(rule.getNotifyResourceOwner())
                || !definitionRegistry.supportsResourceOwner(rule.getSubjectType())) {
            return Optional.empty();
        }
        return listSubjects(definitionRegistry.parseSubjectType(rule.getSubjectType()), rule.getProjectId(), null)
                .stream()
                .filter(descriptor -> (rule.getSubjectId() == null || rule.getSubjectId().equals(descriptor.id))
                        && descriptor.ownerUserId != null)
                .map(descriptor -> descriptor.ownerUserId)
                .findFirst();
    }

    SubjectDescriptor requireSubject(String subjectType, Long subjectId, Long projectId) {
        for (SubjectDescriptor subject : listSubjects(definitionRegistry.parseSubjectType(subjectType), projectId, null)) {
            if (subject.id != null && subject.id.equals(subjectId)) {
                return subject;
            }
        }
        throw new StudioException(StudioErrorCode.NOT_FOUND, "Alert subject was not found in the current project");
    }

    private Set<Long> enabledUserIds(Iterable<Long> userIds, String tenantId) {
        Set<Long> ids = new LinkedHashSet<Long>();
        if (userIds != null) {
            for (Long userId : userIds) {
                if (userId != null) {
                    ids.add(userId);
                }
            }
        }
        if (ids.isEmpty()) {
            return Collections.emptySet();
        }
        List<StudioUserEntity> users = studioUserMapper.selectByIds(ids);
        Set<Long> result = new LinkedHashSet<Long>();
        if (users != null) {
            for (StudioUserEntity user : users) {
                if (user != null && user.getId() != null && Objects.equals(tenantId, user.getTenantId())
                        && Integer.valueOf(1).equals(user.getEnabled())) {
                    result.add(user.getId());
                }
            }
        }
        return result;
    }

    private List<SubjectDescriptor> listSubjects(AlertSubjectType subjectType, Long projectId, String keyword) {
        List<SubjectDescriptor> result = new ArrayList<SubjectDescriptor>();
        String tenantId = securityService.currentTenantId();
        if (subjectType == AlertSubjectType.PROJECT_QUEUE) {
            result.add(new SubjectDescriptor(null, "PROJECT", "项目调度队列", null, null));
            return result;
        }
        if (subjectType == AlertSubjectType.LOG_STORAGE) {
            result.add(new SubjectDescriptor(null, "LOG_STORAGE", "项目日志存储", null, null));
            return result;
        }
        if (subjectType == AlertSubjectType.WORKER_GROUP) {
            List<ProjectWorkerBindingEntity> bindings = projectWorkerBindingMapper.selectList(
                    new LambdaQueryWrapper<ProjectWorkerBindingEntity>()
                            .eq(ProjectWorkerBindingEntity::getTenantId, tenantId)
                            .eq(ProjectWorkerBindingEntity::getProjectId, projectId)
                            .eq(ProjectWorkerBindingEntity::getEnabled, 1)
                            .orderByAsc(ProjectWorkerBindingEntity::getId));
            Set<String> seen = new LinkedHashSet<String>();
            for (ProjectWorkerBindingEntity binding : bindings) {
                if (!Integer.valueOf(1).equals(binding.getEnabled())) {
                    continue;
                }
                String code = binding.getWorkerGroupCode();
                if (StringUtils.hasText(code) && seen.add(code) && matches(code, keyword)) {
                    result.add(new SubjectDescriptor(binding.getId(), code, code, null, null));
                }
            }
            return result;
        }
        if (subjectType == AlertSubjectType.COLLECTION_TASK) {
            for (CollectionTaskDefinitionEntity entity : collectionTaskDefinitionMapper.selectList(
                    new LambdaQueryWrapper<CollectionTaskDefinitionEntity>()
                            .eq(CollectionTaskDefinitionEntity::getTenantId, tenantId)
                            .eq(CollectionTaskDefinitionEntity::getProjectId, projectId)
                            .orderByAsc(CollectionTaskDefinitionEntity::getName))) {
                addSubject(result, entity.getId(), entity.getName(), entity.getCreatedBy(), keyword);
            }
        } else if (subjectType == AlertSubjectType.QUALITY_TASK) {
            for (QualityTaskDefinitionEntity entity : qualityTaskDefinitionMapper.selectList(
                    new LambdaQueryWrapper<QualityTaskDefinitionEntity>()
                            .eq(QualityTaskDefinitionEntity::getTenantId, tenantId)
                            .eq(QualityTaskDefinitionEntity::getProjectId, projectId)
                            .orderByAsc(QualityTaskDefinitionEntity::getTaskName))) {
                addSubject(result, entity.getId(), entity.getTaskName(), entity.getCreatedBy(), keyword);
            }
        } else if (subjectType == AlertSubjectType.WORKFLOW) {
            for (WorkflowDefinitionEntity entity : workflowDefinitionMapper.selectList(
                    new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                            .eq(WorkflowDefinitionEntity::getTenantId, tenantId)
                            .eq(WorkflowDefinitionEntity::getProjectId, projectId)
                            .orderByAsc(WorkflowDefinitionEntity::getName))) {
                addSubject(result, entity.getId(), entity.getName(), entity.getCreatedBy(), keyword);
            }
        } else if (subjectType == AlertSubjectType.DATA_SERVICE) {
            for (DataServiceDefinitionEntity entity : dataServiceDefinitionMapper.selectList(
                    new LambdaQueryWrapper<DataServiceDefinitionEntity>()
                            .eq(DataServiceDefinitionEntity::getTenantId, tenantId)
                            .eq(DataServiceDefinitionEntity::getProjectId, projectId)
                            .orderByAsc(DataServiceDefinitionEntity::getServiceName))) {
                addSubject(result, entity.getId(), entity.getServiceName(), entity.getCreatedBy(), keyword);
            }
        } else if (subjectType == AlertSubjectType.DATA_INGESTION_SERVICE) {
            for (DataIngestionServiceEntity entity : dataIngestionServiceMapper.selectList(
                    new LambdaQueryWrapper<DataIngestionServiceEntity>()
                            .eq(DataIngestionServiceEntity::getTenantId, tenantId)
                            .eq(DataIngestionServiceEntity::getProjectId, projectId)
                            .orderByAsc(DataIngestionServiceEntity::getServiceName))) {
                addSubject(result, entity.getId(), entity.getServiceName(), entity.getCreatedBy(), keyword);
            }
        } else if (subjectType == AlertSubjectType.PROTOCOL_CONVERSION_SERVICE) {
            for (ProtocolConversionServiceEntity entity : protocolConversionServiceMapper.selectList(
                    new LambdaQueryWrapper<ProtocolConversionServiceEntity>()
                            .eq(ProtocolConversionServiceEntity::getTenantId, tenantId)
                            .eq(ProtocolConversionServiceEntity::getProjectId, projectId)
                            .orderByAsc(ProtocolConversionServiceEntity::getServiceName))) {
                addSubject(result, entity.getId(), entity.getServiceName(), entity.getCreatedBy(), keyword);
            }
        }
        return result;
    }

    private void addSubject(List<SubjectDescriptor> result, Long id, String name, Long ownerUserId, String keyword) {
        if (matches(name, keyword)) {
            result.add(new SubjectDescriptor(id, id == null ? null : String.valueOf(id), name, ownerUserId, null));
        }
    }

    private boolean matches(String value, String keyword) {
        return keyword == null || containsIgnoreCase(value, keyword);
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        return value != null && keyword != null && value.toLowerCase().contains(keyword.toLowerCase());
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
        return Math.min(value == null || value.intValue() < 1 ? 20 : value.intValue(), 100);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    static final class SubjectDescriptor {
        private final Long id;
        private final String code;
        private final String name;
        private final Long ownerUserId;
        private final String description;

        private SubjectDescriptor(Long id, String code, String name, Long ownerUserId, String description) {
            this.id = id;
            this.code = code;
            this.name = name;
            this.ownerUserId = ownerUserId;
            this.description = description;
        }

        String name() {
            return name;
        }
    }
}
