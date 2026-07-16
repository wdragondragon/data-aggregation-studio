package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.AlertDeliveryStatus;
import com.jdragon.studio.dto.enums.AlertIncidentStatus;
import com.jdragon.studio.dto.model.AlertSummaryView;
import com.jdragon.studio.dto.model.AlertTenantProjectSummaryView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.request.AlertTenantSummaryQueryRequest;
import com.jdragon.studio.infra.entity.AlertDeliveryEntity;
import com.jdragon.studio.infra.entity.AlertIncidentEntity;
import com.jdragon.studio.infra.entity.AlertRuleEntity;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.mapper.AlertDeliveryMapper;
import com.jdragon.studio.infra.mapper.AlertIncidentMapper;
import com.jdragon.studio.infra.mapper.AlertRuleMapper;
import com.jdragon.studio.infra.mapper.ProjectMapper;
import com.jdragon.studio.infra.model.AlertProjectSummaryAggregate;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AlertIncidentSummarySupport {

    private final AlertIncidentMapper alertIncidentMapper;
    private final AlertDeliveryMapper alertDeliveryMapper;
    private final AlertRuleMapper alertRuleMapper;
    private final ProjectMapper projectMapper;
    private final StudioSecurityService securityService;
    private final ProjectResourceAccessService projectResourceAccessService;

    AlertIncidentSummarySupport(AlertIncidentMapper alertIncidentMapper,
                                AlertDeliveryMapper alertDeliveryMapper,
                                AlertRuleMapper alertRuleMapper,
                                ProjectMapper projectMapper,
                                StudioSecurityService securityService,
                                ProjectResourceAccessService projectResourceAccessService) {
        this.alertIncidentMapper = alertIncidentMapper;
        this.alertDeliveryMapper = alertDeliveryMapper;
        this.alertRuleMapper = alertRuleMapper;
        this.projectMapper = projectMapper;
        this.securityService = securityService;
        this.projectResourceAccessService = projectResourceAccessService;
    }

    AlertSummaryView summary() {
        Long projectId = projectResourceAccessService.requireCurrentProjectId();
        String tenantId = securityService.currentTenantId();
        AlertSummaryView view = new AlertSummaryView();
        view.setEnabledRuleCount(countRules(tenantId, projectId));
        view.setOpenIncidentCount(countIncidents(tenantId, projectId, AlertIncidentStatus.OPEN.name(), null));
        view.setAcknowledgedIncidentCount(countIncidents(
                tenantId, projectId, AlertIncidentStatus.ACKNOWLEDGED.name(), null));
        view.setCriticalIncidentCount(countActiveCritical(tenantId, projectId));
        view.setFailedDeliveryCount(countFailedDeliveries(tenantId, projectId));
        return view;
    }

    PageView<AlertTenantProjectSummaryView> tenantSummary(AlertTenantSummaryQueryRequest request) {
        if (!securityService.hasAnyRole(StudioConstants.ROLE_SUPER_ADMIN, StudioConstants.ROLE_TENANT_ADMIN)) {
            throw new StudioException(StudioErrorCode.FORBIDDEN, "Tenant alert summary permission is required");
        }
        int pageNo = pageNo(request == null ? null : request.getPageNo());
        int pageSize = pageSize(request == null ? null : request.getPageSize());
        String tenantId = securityService.currentTenantId();
        String keyword = request == null ? null : request.getKeyword();
        LambdaQueryWrapper<ProjectEntity> query = new LambdaQueryWrapper<ProjectEntity>()
                .eq(ProjectEntity::getTenantId, tenantId)
                .eq(ProjectEntity::getEnabled, 1)
                .like(StringUtils.hasText(keyword), ProjectEntity::getProjectName,
                        StringUtils.hasText(keyword) ? keyword.trim() : null);
        Long total = projectMapper.selectCount(query);
        List<ProjectEntity> projects = projectMapper.selectList(query.orderByAsc(ProjectEntity::getProjectName)
                .orderByAsc(ProjectEntity::getId)
                .last("limit " + ((pageNo - 1) * pageSize) + "," + pageSize));
        List<Long> projectIds = new ArrayList<Long>();
        for (ProjectEntity project : projects) {
            if (project.getId() != null) {
                projectIds.add(project.getId());
            }
        }
        Map<Long, AlertProjectSummaryAggregate> aggregateByProject = aggregateProjectSummaries(tenantId, projectIds);
        List<AlertTenantProjectSummaryView> items = new ArrayList<AlertTenantProjectSummaryView>();
        for (ProjectEntity project : projects) {
            AlertProjectSummaryAggregate aggregate = aggregateByProject.get(project.getId());
            AlertTenantProjectSummaryView item = new AlertTenantProjectSummaryView();
            item.setProjectId(project.getId());
            item.setProjectName(project.getProjectName());
            item.setEnabledRuleCount(aggregate == null ? 0L : safeLong(aggregate.getEnabledRuleCount()));
            item.setOpenIncidentCount(aggregate == null ? 0L : safeLong(aggregate.getOpenIncidentCount()));
            item.setCriticalIncidentCount(aggregate == null ? 0L : safeLong(aggregate.getCriticalIncidentCount()));
            item.setFailedDeliveryCount(aggregate == null ? 0L : safeLong(aggregate.getFailedDeliveryCount()));
            items.add(item);
        }
        return PageView.of(pageNo, pageSize, total == null ? 0L : total.longValue(), items);
    }

    private Map<Long, AlertProjectSummaryAggregate> aggregateProjectSummaries(String tenantId, List<Long> projectIds) {
        Map<Long, AlertProjectSummaryAggregate> result = new LinkedHashMap<Long, AlertProjectSummaryAggregate>();
        if (projectIds == null || projectIds.isEmpty()) {
            return result;
        }
        mergeProjectAggregates(result, alertRuleMapper.selectEnabledCountsByProjectIds(tenantId, projectIds));
        mergeProjectAggregates(result, alertIncidentMapper.selectIncidentCountsByProjectIds(tenantId, projectIds));
        mergeProjectAggregates(result, alertDeliveryMapper.selectFailedCountsByProjectIds(tenantId, projectIds));
        return result;
    }

    private void mergeProjectAggregates(Map<Long, AlertProjectSummaryAggregate> target,
                                        List<AlertProjectSummaryAggregate> source) {
        if (source == null) {
            return;
        }
        for (AlertProjectSummaryAggregate item : source) {
            if (item == null || item.getProjectId() == null) {
                continue;
            }
            AlertProjectSummaryAggregate aggregate = target.computeIfAbsent(item.getProjectId(), key -> {
                AlertProjectSummaryAggregate created = new AlertProjectSummaryAggregate();
                created.setProjectId(key);
                return created;
            });
            if (item.getEnabledRuleCount() != null) {
                aggregate.setEnabledRuleCount(item.getEnabledRuleCount());
            }
            if (item.getOpenIncidentCount() != null) {
                aggregate.setOpenIncidentCount(item.getOpenIncidentCount());
            }
            if (item.getCriticalIncidentCount() != null) {
                aggregate.setCriticalIncidentCount(item.getCriticalIncidentCount());
            }
            if (item.getFailedDeliveryCount() != null) {
                aggregate.setFailedDeliveryCount(item.getFailedDeliveryCount());
            }
        }
    }

    private long countRules(String tenantId, Long projectId) {
        Long count = alertRuleMapper.selectCount(new LambdaQueryWrapper<AlertRuleEntity>()
                .eq(AlertRuleEntity::getTenantId, tenantId).eq(AlertRuleEntity::getProjectId, projectId)
                .eq(AlertRuleEntity::getEnabled, 1));
        return count == null ? 0L : count.longValue();
    }

    private long countIncidents(String tenantId, Long projectId, String status, String severity) {
        Long count = alertIncidentMapper.selectCount(new LambdaQueryWrapper<AlertIncidentEntity>()
                .eq(AlertIncidentEntity::getTenantId, tenantId).eq(AlertIncidentEntity::getProjectId, projectId)
                .eq(StringUtils.hasText(status), AlertIncidentEntity::getStatus, status)
                .eq(StringUtils.hasText(severity), AlertIncidentEntity::getSeverity, severity));
        return count == null ? 0L : count.longValue();
    }

    private long countActiveCritical(String tenantId, Long projectId) {
        Long count = alertIncidentMapper.selectCount(new LambdaQueryWrapper<AlertIncidentEntity>()
                .eq(AlertIncidentEntity::getTenantId, tenantId).eq(AlertIncidentEntity::getProjectId, projectId)
                .eq(AlertIncidentEntity::getSeverity, "CRITICAL")
                .in(AlertIncidentEntity::getStatus,
                        AlertIncidentStatus.OPEN.name(), AlertIncidentStatus.ACKNOWLEDGED.name()));
        return count == null ? 0L : count.longValue();
    }

    private long countFailedDeliveries(String tenantId, Long projectId) {
        Long count = alertDeliveryMapper.selectCount(new LambdaQueryWrapper<AlertDeliveryEntity>()
                .eq(AlertDeliveryEntity::getTenantId, tenantId).eq(AlertDeliveryEntity::getProjectId, projectId)
                .in(AlertDeliveryEntity::getStatus,
                        AlertDeliveryStatus.RETRY.name(), AlertDeliveryStatus.DEAD.name()));
        return count == null ? 0L : count.longValue();
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value.longValue();
    }

    private int pageNo(Integer value) {
        return value == null || value.intValue() < 1 ? 1 : value.intValue();
    }

    private int pageSize(Integer value) {
        return Math.min(value == null || value.intValue() < 1 ? 20 : value.intValue(), 100);
    }
}
