package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.system.ShareResourceOptionView;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.DataDevelopmentScriptEntity;
import com.jdragon.studio.infra.entity.DataIngestionServiceEntity;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.entity.ProtocolConversionServiceEntity;
import com.jdragon.studio.infra.entity.ResourceShareEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataDevelopmentScriptMapper;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;

import java.util.ArrayList;
import java.util.List;

final class SystemResourceShareSupport {

    private final DatasourceMapper datasourceMapper;
    private final DataModelMapper dataModelMapper;
    private final CollectionTaskDefinitionMapper collectionTaskDefinitionMapper;
    private final WorkflowDefinitionMapper workflowDefinitionMapper;
    private final DataDevelopmentScriptMapper dataDevelopmentScriptMapper;
    private final DataServiceDefinitionMapper dataServiceDefinitionMapper;
    private final DataIngestionServiceMapper dataIngestionServiceMapper;
    private final ProtocolConversionServiceMapper protocolConversionServiceMapper;
    private final NotificationService notificationService;

    SystemResourceShareSupport(DatasourceMapper datasourceMapper,
                               DataModelMapper dataModelMapper,
                               CollectionTaskDefinitionMapper collectionTaskDefinitionMapper,
                               WorkflowDefinitionMapper workflowDefinitionMapper,
                               DataDevelopmentScriptMapper dataDevelopmentScriptMapper,
                               DataServiceDefinitionMapper dataServiceDefinitionMapper,
                               DataIngestionServiceMapper dataIngestionServiceMapper,
                               ProtocolConversionServiceMapper protocolConversionServiceMapper,
                               NotificationService notificationService) {
        this.datasourceMapper = datasourceMapper;
        this.dataModelMapper = dataModelMapper;
        this.collectionTaskDefinitionMapper = collectionTaskDefinitionMapper;
        this.workflowDefinitionMapper = workflowDefinitionMapper;
        this.dataDevelopmentScriptMapper = dataDevelopmentScriptMapper;
        this.dataServiceDefinitionMapper = dataServiceDefinitionMapper;
        this.dataIngestionServiceMapper = dataIngestionServiceMapper;
        this.protocolConversionServiceMapper = protocolConversionServiceMapper;
        this.notificationService = notificationService;
    }

    void validateShareableResource(String tenantId, Long sourceProjectId, String resourceType, Long resourceId) {
        if (StudioConstants.RESOURCE_TYPE_DATASOURCE.equals(resourceType)) {
            DatasourceEntity entity = datasourceMapper.selectById(resourceId);
            ensureShareableResource(entity == null ? null : entity.getTenantId(),
                    entity == null ? null : entity.getProjectId(), resourceId, sourceProjectId, tenantId, "Datasource");
            return;
        }
        if (StudioConstants.RESOURCE_TYPE_DATA_MODEL.equals(resourceType)) {
            DataModelEntity entity = dataModelMapper.selectById(resourceId);
            ensureShareableResource(entity == null ? null : entity.getTenantId(),
                    entity == null ? null : entity.getProjectId(), resourceId, sourceProjectId, tenantId, "Model");
            return;
        }
        if (StudioConstants.RESOURCE_TYPE_COLLECTION_TASK.equals(resourceType)) {
            CollectionTaskDefinitionEntity entity = collectionTaskDefinitionMapper.selectById(resourceId);
            ensureShareableResource(entity == null ? null : entity.getTenantId(),
                    entity == null ? null : entity.getProjectId(), resourceId, sourceProjectId, tenantId, "Collection task");
            return;
        }
        if (StudioConstants.RESOURCE_TYPE_WORKFLOW.equals(resourceType)) {
            WorkflowDefinitionEntity entity = workflowDefinitionMapper.selectById(resourceId);
            ensureShareableResource(entity == null ? null : entity.getTenantId(),
                    entity == null ? null : entity.getProjectId(), resourceId, sourceProjectId, tenantId, "Workflow");
            return;
        }
        if (StudioConstants.RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT.equals(resourceType)) {
            DataDevelopmentScriptEntity entity = dataDevelopmentScriptMapper.selectById(resourceId);
            ensureShareableResource(entity == null ? null : entity.getTenantId(),
                    entity == null ? null : entity.getProjectId(), resourceId, sourceProjectId, tenantId, "Data development script");
            return;
        }
        if (StudioConstants.RESOURCE_TYPE_DATA_SERVICE.equals(resourceType)) {
            DataServiceDefinitionEntity entity = dataServiceDefinitionMapper.selectById(resourceId);
            ensureShareableResource(entity == null ? null : entity.getTenantId(),
                    entity == null ? null : entity.getProjectId(), resourceId, sourceProjectId, tenantId, "Data service");
            return;
        }
        if (StudioConstants.RESOURCE_TYPE_DATA_INGESTION_SERVICE.equals(resourceType)) {
            DataIngestionServiceEntity entity = dataIngestionServiceMapper.selectById(resourceId);
            ensureShareableResource(entity == null ? null : entity.getTenantId(),
                    entity == null ? null : entity.getProjectId(), resourceId, sourceProjectId, tenantId, "Data ingestion service");
            return;
        }
        if (StudioConstants.RESOURCE_TYPE_PROTOCOL_CONVERSION_SERVICE.equals(resourceType)) {
            ProtocolConversionServiceEntity entity = protocolConversionServiceMapper.selectById(resourceId);
            ensureShareableResource(entity == null ? null : entity.getTenantId(),
                    entity == null ? null : entity.getProjectId(), resourceId, sourceProjectId, tenantId, "Protocol conversion service");
            return;
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported resource type for sharing: " + resourceType);
    }

    List<ShareResourceOptionView> listShareResourceOptions(String tenantId, Long sourceProjectId, String resourceType) {
        if (StudioConstants.RESOURCE_TYPE_DATASOURCE.equals(resourceType)) {
            List<ShareResourceOptionView> result = new ArrayList<ShareResourceOptionView>();
            List<DatasourceEntity> entities = datasourceMapper.selectList(new LambdaQueryWrapper<DatasourceEntity>()
                    .select(DatasourceEntity::getId,
                            DatasourceEntity::getTenantId,
                            DatasourceEntity::getProjectId,
                            DatasourceEntity::getDeleted,
                            DatasourceEntity::getName,
                            DatasourceEntity::getTypeCode,
                            DatasourceEntity::getEnabled)
                    .eq(DatasourceEntity::getTenantId, tenantId)
                    .eq(DatasourceEntity::getProjectId, sourceProjectId)
                    .orderByAsc(DatasourceEntity::getName)
                    .orderByAsc(DatasourceEntity::getId));
            for (DatasourceEntity entity : entities) {
                result.add(option(entity.getId(), entity.getTenantId(), entity.getProjectId(), entity.getDeleted(),
                        resourceType, entity.getName(), entity.getTypeCode(), statusText(entity.getEnabled()),
                        entity.getName() + " (" + entity.getTypeCode() + ")"));
            }
            return result;
        }
        if (StudioConstants.RESOURCE_TYPE_DATA_MODEL.equals(resourceType)) {
            List<ShareResourceOptionView> result = new ArrayList<ShareResourceOptionView>();
            List<DataModelEntity> entities = dataModelMapper.selectList(new LambdaQueryWrapper<DataModelEntity>()
                    .select(DataModelEntity::getId,
                            DataModelEntity::getTenantId,
                            DataModelEntity::getProjectId,
                            DataModelEntity::getDeleted,
                            DataModelEntity::getName,
                            DataModelEntity::getPhysicalLocator)
                    .eq(DataModelEntity::getTenantId, tenantId)
                    .eq(DataModelEntity::getProjectId, sourceProjectId)
                    .orderByAsc(DataModelEntity::getName)
                    .orderByAsc(DataModelEntity::getId));
            for (DataModelEntity entity : entities) {
                result.add(option(entity.getId(), entity.getTenantId(), entity.getProjectId(), entity.getDeleted(),
                        resourceType, entity.getName(), entity.getPhysicalLocator(), null,
                        entity.getName() + " / " + nullSafe(entity.getPhysicalLocator())));
            }
            return result;
        }
        if (StudioConstants.RESOURCE_TYPE_COLLECTION_TASK.equals(resourceType)) {
            List<ShareResourceOptionView> result = new ArrayList<ShareResourceOptionView>();
            List<CollectionTaskDefinitionEntity> entities = collectionTaskDefinitionMapper.selectList(new LambdaQueryWrapper<CollectionTaskDefinitionEntity>()
                    .select(CollectionTaskDefinitionEntity::getId,
                            CollectionTaskDefinitionEntity::getTenantId,
                            CollectionTaskDefinitionEntity::getProjectId,
                            CollectionTaskDefinitionEntity::getDeleted,
                            CollectionTaskDefinitionEntity::getName,
                            CollectionTaskDefinitionEntity::getStatus)
                    .eq(CollectionTaskDefinitionEntity::getTenantId, tenantId)
                    .eq(CollectionTaskDefinitionEntity::getProjectId, sourceProjectId)
                    .orderByAsc(CollectionTaskDefinitionEntity::getName)
                    .orderByAsc(CollectionTaskDefinitionEntity::getId));
            for (CollectionTaskDefinitionEntity entity : entities) {
                result.add(option(entity.getId(), entity.getTenantId(), entity.getProjectId(), entity.getDeleted(),
                        resourceType, entity.getName(), null, entity.getStatus(), entity.getName()));
            }
            return result;
        }
        if (StudioConstants.RESOURCE_TYPE_WORKFLOW.equals(resourceType)) {
            List<ShareResourceOptionView> result = new ArrayList<ShareResourceOptionView>();
            List<WorkflowDefinitionEntity> entities = workflowDefinitionMapper.selectList(new LambdaQueryWrapper<WorkflowDefinitionEntity>()
                    .select(WorkflowDefinitionEntity::getId,
                            WorkflowDefinitionEntity::getTenantId,
                            WorkflowDefinitionEntity::getProjectId,
                            WorkflowDefinitionEntity::getDeleted,
                            WorkflowDefinitionEntity::getName,
                            WorkflowDefinitionEntity::getCode,
                            WorkflowDefinitionEntity::getPublished)
                    .eq(WorkflowDefinitionEntity::getTenantId, tenantId)
                    .eq(WorkflowDefinitionEntity::getProjectId, sourceProjectId)
                    .orderByAsc(WorkflowDefinitionEntity::getName)
                    .orderByAsc(WorkflowDefinitionEntity::getId));
            for (WorkflowDefinitionEntity entity : entities) {
                result.add(option(entity.getId(), entity.getTenantId(), entity.getProjectId(), entity.getDeleted(),
                        resourceType, entity.getName(), entity.getCode(), statusText(entity.getPublished()),
                        entity.getName() + " (" + entity.getCode() + ")"));
            }
            return result;
        }
        if (StudioConstants.RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT.equals(resourceType)) {
            List<ShareResourceOptionView> result = new ArrayList<ShareResourceOptionView>();
            List<DataDevelopmentScriptEntity> entities = dataDevelopmentScriptMapper.selectList(new LambdaQueryWrapper<DataDevelopmentScriptEntity>()
                    .select(DataDevelopmentScriptEntity::getId,
                            DataDevelopmentScriptEntity::getTenantId,
                            DataDevelopmentScriptEntity::getProjectId,
                            DataDevelopmentScriptEntity::getDeleted,
                            DataDevelopmentScriptEntity::getFileName,
                            DataDevelopmentScriptEntity::getScriptType)
                    .eq(DataDevelopmentScriptEntity::getTenantId, tenantId)
                    .eq(DataDevelopmentScriptEntity::getProjectId, sourceProjectId)
                    .orderByAsc(DataDevelopmentScriptEntity::getFileName)
                    .orderByAsc(DataDevelopmentScriptEntity::getId));
            for (DataDevelopmentScriptEntity entity : entities) {
                result.add(option(entity.getId(), entity.getTenantId(), entity.getProjectId(), entity.getDeleted(),
                        resourceType, entity.getFileName(), entity.getScriptType(), null,
                        entity.getFileName() + " (" + entity.getScriptType() + ")"));
            }
            return result;
        }
        if (StudioConstants.RESOURCE_TYPE_DATA_SERVICE.equals(resourceType)) {
            List<ShareResourceOptionView> result = new ArrayList<ShareResourceOptionView>();
            List<DataServiceDefinitionEntity> entities = dataServiceDefinitionMapper.selectList(new LambdaQueryWrapper<DataServiceDefinitionEntity>()
                    .select(DataServiceDefinitionEntity::getId,
                            DataServiceDefinitionEntity::getTenantId,
                            DataServiceDefinitionEntity::getProjectId,
                            DataServiceDefinitionEntity::getDeleted,
                            DataServiceDefinitionEntity::getServiceName,
                            DataServiceDefinitionEntity::getServiceCode,
                            DataServiceDefinitionEntity::getStatus)
                    .eq(DataServiceDefinitionEntity::getTenantId, tenantId)
                    .eq(DataServiceDefinitionEntity::getProjectId, sourceProjectId)
                    .orderByAsc(DataServiceDefinitionEntity::getServiceName)
                    .orderByAsc(DataServiceDefinitionEntity::getId));
            for (DataServiceDefinitionEntity entity : entities) {
                result.add(option(entity.getId(), entity.getTenantId(), entity.getProjectId(), entity.getDeleted(),
                        resourceType, entity.getServiceName(), entity.getServiceCode(), entity.getStatus(),
                        entity.getServiceName() + " (" + entity.getServiceCode() + ")"));
            }
            return result;
        }
        if (StudioConstants.RESOURCE_TYPE_DATA_INGESTION_SERVICE.equals(resourceType)) {
            List<ShareResourceOptionView> result = new ArrayList<ShareResourceOptionView>();
            List<DataIngestionServiceEntity> entities = dataIngestionServiceMapper.selectList(new LambdaQueryWrapper<DataIngestionServiceEntity>()
                    .select(DataIngestionServiceEntity::getId,
                            DataIngestionServiceEntity::getTenantId,
                            DataIngestionServiceEntity::getProjectId,
                            DataIngestionServiceEntity::getDeleted,
                            DataIngestionServiceEntity::getServiceName,
                            DataIngestionServiceEntity::getServiceCode,
                            DataIngestionServiceEntity::getStatus)
                    .eq(DataIngestionServiceEntity::getTenantId, tenantId)
                    .eq(DataIngestionServiceEntity::getProjectId, sourceProjectId)
                    .orderByAsc(DataIngestionServiceEntity::getServiceName)
                    .orderByAsc(DataIngestionServiceEntity::getId));
            for (DataIngestionServiceEntity entity : entities) {
                result.add(option(entity.getId(), entity.getTenantId(), entity.getProjectId(), entity.getDeleted(),
                        resourceType, entity.getServiceName(), entity.getServiceCode(), entity.getStatus(),
                        entity.getServiceName() + " (" + entity.getServiceCode() + ")"));
            }
            return result;
        }
        if (StudioConstants.RESOURCE_TYPE_PROTOCOL_CONVERSION_SERVICE.equals(resourceType)) {
            List<ShareResourceOptionView> result = new ArrayList<ShareResourceOptionView>();
            List<ProtocolConversionServiceEntity> entities = protocolConversionServiceMapper.selectList(new LambdaQueryWrapper<ProtocolConversionServiceEntity>()
                    .select(ProtocolConversionServiceEntity::getId,
                            ProtocolConversionServiceEntity::getTenantId,
                            ProtocolConversionServiceEntity::getProjectId,
                            ProtocolConversionServiceEntity::getDeleted,
                            ProtocolConversionServiceEntity::getServiceName,
                            ProtocolConversionServiceEntity::getServiceCode,
                            ProtocolConversionServiceEntity::getStatus)
                    .eq(ProtocolConversionServiceEntity::getTenantId, tenantId)
                    .eq(ProtocolConversionServiceEntity::getProjectId, sourceProjectId)
                    .orderByAsc(ProtocolConversionServiceEntity::getServiceName)
                    .orderByAsc(ProtocolConversionServiceEntity::getId));
            for (ProtocolConversionServiceEntity entity : entities) {
                result.add(option(entity.getId(), entity.getTenantId(), entity.getProjectId(), entity.getDeleted(),
                        resourceType, entity.getServiceName(), entity.getServiceCode(), entity.getStatus(),
                        entity.getServiceName() + " (" + entity.getServiceCode() + ")"));
            }
            return result;
        }
        throw new StudioException(StudioErrorCode.BAD_REQUEST, "Unsupported resource type for sharing: " + resourceType);
    }

    void notifyResourceShare(ResourceShareEntity share, ProjectEntity targetProject) {
        if (share == null || targetProject == null) {
            return;
        }
        List<Long> recipientUserIds = notificationService.activeProjectMemberUserIds(targetProject.getTenantId(), targetProject.getId());
        if (recipientUserIds.isEmpty()) {
            return;
        }
        notificationService.notifyUsers(recipientUserIds,
                new NotificationCommand()
                        .setCategory(StudioConstants.NOTIFICATION_CATEGORY_RESOURCE_SHARE)
                        .setTitle("收到新的共享资源")
                        .setContent("项目 " + targetProject.getProjectName() + " 收到了共享资源：" + resolveSharedResourceLabel(share) + "。")
                        .setTargetType(share.getResourceType())
                        .setTargetId(share.getResourceId())
                        .setTargetPath(resolveShareTargetPath(share))
                        .setTargetTenantId(targetProject.getTenantId())
                        .setTargetProjectId(targetProject.getId())
                        .setDedupeKey("resource-share:" + share.getId() + ":" + targetProject.getId() + ":" + (share.getUpdatedAt() == null ? "0" : share.getUpdatedAt().toString())));
    }

    private void ensureShareableResource(String resourceTenantId,
                                         Long resourceProjectId,
                                         Long resourceId,
                                         Long sourceProjectId,
                                         String tenantId,
                                         String resourceName) {
        if (!tenantId.equals(resourceTenantId) || resourceProjectId == null || !resourceProjectId.equals(sourceProjectId)) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, resourceName + " not found: " + resourceId);
        }
    }

    private String resolveSharedResourceLabel(ResourceShareEntity share) {
        if (share == null || !hasText(share.getResourceType()) || share.getResourceId() == null) {
            return "未知资源";
        }
        String resourceType = share.getResourceType().trim().toUpperCase();
        if (StudioConstants.RESOURCE_TYPE_DATASOURCE.equals(resourceType)) {
            DatasourceEntity entity = datasourceMapper.selectById(share.getResourceId());
            return entity == null ? "数据源#" + share.getResourceId() : "数据源 " + entity.getName();
        }
        if (StudioConstants.RESOURCE_TYPE_DATA_MODEL.equals(resourceType)) {
            DataModelEntity entity = dataModelMapper.selectById(share.getResourceId());
            return entity == null ? "模型#" + share.getResourceId() : "模型 " + entity.getName();
        }
        if (StudioConstants.RESOURCE_TYPE_COLLECTION_TASK.equals(resourceType)) {
            CollectionTaskDefinitionEntity entity = collectionTaskDefinitionMapper.selectById(share.getResourceId());
            return entity == null ? "采集任务#" + share.getResourceId() : "采集任务 " + entity.getName();
        }
        if (StudioConstants.RESOURCE_TYPE_WORKFLOW.equals(resourceType)) {
            WorkflowDefinitionEntity entity = workflowDefinitionMapper.selectById(share.getResourceId());
            return entity == null ? "工作流#" + share.getResourceId() : "工作流 " + entity.getName();
        }
        if (StudioConstants.RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT.equals(resourceType)) {
            DataDevelopmentScriptEntity entity = dataDevelopmentScriptMapper.selectById(share.getResourceId());
            return entity == null ? "数据开发脚本#" + share.getResourceId() : "数据开发脚本 " + entity.getFileName();
        }
        if (StudioConstants.RESOURCE_TYPE_DATA_SERVICE.equals(resourceType)) {
            DataServiceDefinitionEntity entity = dataServiceDefinitionMapper.selectById(share.getResourceId());
            return entity == null ? "数据服务#" + share.getResourceId() : "数据服务 " + entity.getServiceName();
        }
        if (StudioConstants.RESOURCE_TYPE_DATA_INGESTION_SERVICE.equals(resourceType)) {
            DataIngestionServiceEntity entity = dataIngestionServiceMapper.selectById(share.getResourceId());
            return entity == null ? "数据接入服务#" + share.getResourceId() : "数据接入服务 " + entity.getServiceName();
        }
        if (StudioConstants.RESOURCE_TYPE_PROTOCOL_CONVERSION_SERVICE.equals(resourceType)) {
            ProtocolConversionServiceEntity entity = protocolConversionServiceMapper.selectById(share.getResourceId());
            return entity == null ? "协议转换服务#" + share.getResourceId() : "协议转换服务 " + entity.getServiceName();
        }
        return share.getResourceType() + "#" + share.getResourceId();
    }

    private String resolveShareTargetPath(ResourceShareEntity share) {
        if (share == null || !hasText(share.getResourceType()) || share.getResourceId() == null) {
            return "/dashboard";
        }
        String resourceType = share.getResourceType().trim().toUpperCase();
        if (StudioConstants.RESOURCE_TYPE_DATASOURCE.equals(resourceType)) {
            return "/datasources";
        }
        if (StudioConstants.RESOURCE_TYPE_DATA_MODEL.equals(resourceType)) {
            return "/models/" + share.getResourceId();
        }
        if (StudioConstants.RESOURCE_TYPE_COLLECTION_TASK.equals(resourceType)) {
            return "/collection-tasks";
        }
        if (StudioConstants.RESOURCE_TYPE_WORKFLOW.equals(resourceType)) {
            return "/workflows/" + share.getResourceId();
        }
        if (StudioConstants.RESOURCE_TYPE_DATA_DEVELOPMENT_SCRIPT.equals(resourceType)) {
            return "/data-development";
        }
        if (StudioConstants.RESOURCE_TYPE_DATA_SERVICE.equals(resourceType)) {
            return "/data-services";
        }
        if (StudioConstants.RESOURCE_TYPE_DATA_INGESTION_SERVICE.equals(resourceType)) {
            return "/data-ingestion-services";
        }
        if (StudioConstants.RESOURCE_TYPE_PROTOCOL_CONVERSION_SERVICE.equals(resourceType)) {
            return "/protocol-conversions";
        }
        return "/dashboard";
    }

    private ShareResourceOptionView option(Long id,
                                           String tenantId,
                                           Long projectId,
                                           Integer deleted,
                                           String resourceType,
                                           String name,
                                           String code,
                                           String status,
                                           String label) {
        ShareResourceOptionView view = new ShareResourceOptionView();
        view.setId(id);
        view.setTenantId(tenantId);
        view.setProjectId(projectId);
        view.setDeleted(deleted != null && deleted.intValue() == 1);
        view.setResourceType(resourceType);
        view.setName(name);
        view.setCode(code);
        view.setStatus(status);
        view.setLabel(hasText(label) ? label : name);
        return view;
    }

    private String statusText(Integer value) {
        if (value == null) {
            return null;
        }
        return value.intValue() == 1 ? "ENABLED" : "DISABLED";
    }

    private String nullSafe(String value) {
        return hasText(value) ? value : "-";
    }

    private boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }
}
