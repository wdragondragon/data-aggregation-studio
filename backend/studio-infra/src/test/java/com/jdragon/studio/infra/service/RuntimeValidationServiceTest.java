package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.DatasourceClusterBindingImpactView;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.DatasourceClusterBindingEntity;
import com.jdragon.studio.infra.entity.RuntimeValidationEntity;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.entity.WorkflowDefinitionEntity;
import com.jdragon.studio.infra.entity.WorkflowVersionEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataDevelopmentScriptMapper;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.DatasourceClusterBindingMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.QualityTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.RuntimeValidationMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.ProjectRuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.mapper.WorkflowVersionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeValidationServiceTest {

    @Test
    void shouldPreviewAndPersistInvalidationWhenDatasourceClusterBindingIsRemoved() {
        RuntimeValidationMapper validationMapper = mock(RuntimeValidationMapper.class);
        DatasourceClusterBindingMapper bindingMapper = mock(DatasourceClusterBindingMapper.class);
        CollectionTaskDefinitionMapper collectionMapper = mock(CollectionTaskDefinitionMapper.class);
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding(99L, 10L)));
        CollectionTaskDefinitionEntity collection = new CollectionTaskDefinitionEntity();
        collection.setId(301L);
        collection.setTenantId("tenant-a");
        collection.setProjectId(200L);
        collection.setName("采集任务");
        collection.setRuntimeClusterId(10L);
        collection.setSourceBindingsJson(List.of(Map.of("datasourceId", 99L)));
        when(collectionMapper.selectList(any())).thenReturn(List.of(collection));
        when(collectionMapper.selectById(301L)).thenReturn(collection);

        Fixture fixture = fixture(validationMapper, bindingMapper, collectionMapper);
        DatasourceClusterBindingImpactView impact = fixture.service.previewDatasourceBindingImpact(99L, List.of());

        assertEquals(List.of(10L), impact.getRemovedClusterIds());
        assertEquals(1, impact.getAffectedResources().size());
        assertEquals(StudioConstants.RESOURCE_TYPE_COLLECTION_TASK, impact.getAffectedResources().get(0).getResourceType());
        assertEquals(301L, impact.getAffectedResources().get(0).getResourceId());

        fixture.service.applyDatasourceBindingImpact(impact);

        ArgumentCaptor<RuntimeValidationEntity> captor = ArgumentCaptor.forClass(RuntimeValidationEntity.class);
        verify(validationMapper).insert(captor.capture());
        RuntimeValidationEntity validation = captor.getValue();
        assertEquals(200L, validation.getProjectId());
        assertEquals(0, validation.getValid());
        assertEquals(RuntimeValidationService.ISSUE_DATASOURCE_CLUSTER_REMOVED, validation.getIssueCode());
        assertEquals(99L, validation.getDetailsJson().get("datasourceId"));
        assertEquals(List.of(99L), validation.getDetailsJson().get("datasourceIds"));
        assertEquals(List.of(99L), validation.getDetailsJson().get("missingDatasourceIds"));
    }

    @Test
    void shouldKeepResourceInvalidWhenUnrelatedClusterIsAdded() {
        RuntimeValidationMapper validationMapper = mock(RuntimeValidationMapper.class);
        DatasourceClusterBindingMapper bindingMapper = mock(DatasourceClusterBindingMapper.class);
        CollectionTaskDefinitionMapper collectionMapper = mock(CollectionTaskDefinitionMapper.class);
        RuntimeValidationEntity existing = new RuntimeValidationEntity();
        existing.setId(88L);
        existing.setTenantId("tenant-a");
        existing.setProjectId(100L);
        existing.setResourceType(StudioConstants.RESOURCE_TYPE_COLLECTION_TASK);
        existing.setResourceId(301L);
        existing.setRuntimeClusterId(10L);
        existing.setValid(0);
        existing.setIssueCode(RuntimeValidationService.ISSUE_DATASOURCE_CLUSTER_REMOVED);
        existing.setDetailsJson(Map.of("datasourceId", 99L));
        CollectionTaskDefinitionEntity collection = new CollectionTaskDefinitionEntity();
        collection.setId(301L);
        collection.setTenantId("tenant-a");
        collection.setProjectId(100L);
        collection.setRuntimeClusterId(10L);
        collection.setSourceBindingsJson(List.of(Map.of("datasourceId", 99L)));
        when(collectionMapper.selectById(301L)).thenReturn(collection);
        when(bindingMapper.selectCount(any())).thenReturn(0L);

        Fixture fixture = fixture(validationMapper, bindingMapper, collectionMapper);
        when(validationMapper.selectList(any())).thenReturn(List.of(existing));
        DatasourceClusterBindingImpactView impact = new DatasourceClusterBindingImpactView();
        impact.setDatasourceId(99L);

        fixture.service.applyDatasourceBindingImpact(impact);

        verify(validationMapper).updateById(existing);
        assertEquals(0, existing.getValid());
        assertEquals(List.of(99L), existing.getDetailsJson().get("missingDatasourceIds"));
    }

    @Test
    void shouldQueryInvalidResourcesForCurrentProject() {
        RuntimeValidationMapper validationMapper = mock(RuntimeValidationMapper.class);
        DatasourceClusterBindingMapper bindingMapper = mock(DatasourceClusterBindingMapper.class);
        CollectionTaskDefinitionMapper collectionMapper = mock(CollectionTaskDefinitionMapper.class);
        RuntimeValidationEntity existing = new RuntimeValidationEntity();
        existing.setProjectId(100L);
        existing.setResourceType(StudioConstants.RESOURCE_TYPE_COLLECTION_TASK);
        existing.setResourceId(301L);
        existing.setRuntimeClusterId(10L);
        existing.setValid(0);
        existing.setIssueCode(RuntimeValidationService.ISSUE_DATASOURCE_CLUSTER_REMOVED);
        existing.setIssueMessage("invalid runtime");
        existing.setDetailsJson(Map.of("datasourceId", 99L));
        Fixture fixture = fixture(validationMapper, bindingMapper, collectionMapper);
        when(validationMapper.selectList(any())).thenReturn(List.of(existing));
        List<com.jdragon.studio.dto.model.RuntimeValidationView> result = fixture.service.queryInvalid(
                StudioConstants.RESOURCE_TYPE_COLLECTION_TASK, List.of(301L));

        assertEquals(1, result.size());
        assertEquals(100L, result.get(0).getProjectId());
        assertEquals(301L, result.get(0).getResourceId());
        assertEquals(99L, result.get(0).getDetails().get("datasourceId"));
        assertFalse(result.get(0).isValid());
    }

    @Test
    void shouldHydrateRuntimeValidityByResourceProject() {
        RuntimeValidationMapper validationMapper = mock(RuntimeValidationMapper.class);
        DatasourceClusterBindingMapper bindingMapper = mock(DatasourceClusterBindingMapper.class);
        CollectionTaskDefinitionMapper collectionMapper = mock(CollectionTaskDefinitionMapper.class);
        RuntimeValidationEntity invalid = new RuntimeValidationEntity();
        invalid.setProjectId(200L);
        invalid.setResourceType(StudioConstants.RESOURCE_TYPE_COLLECTION_TASK);
        invalid.setResourceId(301L);
        invalid.setValid(0);
        invalid.setIssueMessage("运行集群缺少数据源绑定");
        Fixture fixture = fixture(validationMapper, bindingMapper, collectionMapper);
        when(validationMapper.selectList(any())).thenReturn(List.of(invalid));
        CollectionTaskDefinitionView currentProjectView = new CollectionTaskDefinitionView();
        currentProjectView.setId(301L);
        currentProjectView.setProjectId(100L);
        CollectionTaskDefinitionView sharedProjectView = new CollectionTaskDefinitionView();
        sharedProjectView.setId(301L);
        sharedProjectView.setProjectId(200L);

        fixture.service.hydrate(StudioConstants.RESOURCE_TYPE_COLLECTION_TASK,
                List.of(currentProjectView, sharedProjectView));

        assertTrue(currentProjectView.getRuntimeValid());
        assertNull(currentProjectView.getRuntimeValidationMessage());
        assertFalse(sharedProjectView.getRuntimeValid());
        assertEquals("运行集群缺少数据源绑定", sharedProjectView.getRuntimeValidationMessage());
    }

    @Test
    void shouldKeepCompoundResourceInvalidUntilEveryDatasourceBindingRecovers() {
        RuntimeValidationMapper validationMapper = mock(RuntimeValidationMapper.class);
        DatasourceClusterBindingMapper bindingMapper = mock(DatasourceClusterBindingMapper.class);
        CollectionTaskDefinitionMapper collectionMapper = mock(CollectionTaskDefinitionMapper.class);
        RuntimeValidationEntity existing = new RuntimeValidationEntity();
        existing.setId(88L);
        existing.setTenantId("tenant-a");
        existing.setProjectId(200L);
        existing.setResourceType(StudioConstants.RESOURCE_TYPE_COLLECTION_TASK);
        existing.setResourceId(301L);
        existing.setRuntimeClusterId(10L);
        existing.setValid(0);
        existing.setIssueCode(RuntimeValidationService.ISSUE_DATASOURCE_CLUSTER_REMOVED);
        existing.setDetailsJson(Map.of("datasourceIds", List.of(99L, 100L)));
        CollectionTaskDefinitionEntity collection = new CollectionTaskDefinitionEntity();
        collection.setId(301L);
        collection.setTenantId("tenant-a");
        collection.setProjectId(200L);
        collection.setRuntimeClusterId(10L);
        collection.setSourceBindingsJson(List.of(Map.of("datasourceId", 99L)));
        collection.setTargetBindingJson(Map.of("datasourceId", 100L));
        when(collectionMapper.selectById(301L)).thenReturn(collection);
        when(bindingMapper.selectCount(any())).thenReturn(1L, 0L, 1L, 1L);
        Fixture fixture = fixture(validationMapper, bindingMapper, collectionMapper);
        when(validationMapper.selectList(any())).thenReturn(List.of(existing));
        DatasourceClusterBindingImpactView impact = new DatasourceClusterBindingImpactView();
        impact.setDatasourceId(99L);

        fixture.service.applyDatasourceBindingImpact(impact);

        assertEquals(0, existing.getValid());
        assertEquals(List.of(100L), existing.getDetailsJson().get("missingDatasourceIds"));

        fixture.service.applyDatasourceBindingImpact(impact);

        assertEquals(1, existing.getValid());
        assertNull(existing.getIssueCode());
        assertTrue(existing.getDetailsJson().isEmpty());
        verify(validationMapper, org.mockito.Mockito.times(2)).updateById(existing);
    }

    @Test
    void shouldInvalidateWorkflowThatIndirectlyReferencesDatasourceThroughCollectionNode() {
        RuntimeValidationMapper validationMapper = mock(RuntimeValidationMapper.class);
        DatasourceClusterBindingMapper bindingMapper = mock(DatasourceClusterBindingMapper.class);
        CollectionTaskDefinitionMapper collectionMapper = mock(CollectionTaskDefinitionMapper.class);
        WorkflowDefinitionMapper workflowMapper = mock(WorkflowDefinitionMapper.class);
        WorkflowVersionMapper workflowVersionMapper = mock(WorkflowVersionMapper.class);
        when(bindingMapper.selectList(any())).thenReturn(List.of(binding(99L, 10L)));

        CollectionTaskDefinitionEntity collection = new CollectionTaskDefinitionEntity();
        collection.setId(301L);
        collection.setTenantId("tenant-a");
        collection.setProjectId(200L);
        collection.setRuntimeClusterId(10L);
        collection.setSourceBindingsJson(List.of(Map.of("datasourceId", 99L)));
        when(collectionMapper.selectList(any())).thenReturn(List.of(collection));
        when(collectionMapper.selectById(301L)).thenReturn(collection);

        WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
        workflow.setId(401L);
        workflow.setTenantId("tenant-a");
        workflow.setProjectId(200L);
        workflow.setRuntimeClusterId(10L);
        workflow.setCurrentVersionId(501L);
        workflow.setName("间接依赖工作流");
        WorkflowVersionEntity version = new WorkflowVersionEntity();
        version.setId(501L);
        version.setDefinitionId(401L);
        version.setGraphJson(Map.of("nodes", List.of(Map.of(
                "nodeType", "COLLECTION_TASK",
                "config", Map.of("collectionTaskId", 301L)))));
        when(workflowMapper.selectList(any())).thenReturn(List.of(workflow));
        when(workflowVersionMapper.selectById(501L)).thenReturn(version);

        Fixture fixture = fixture(validationMapper, bindingMapper, collectionMapper,
                workflowMapper, workflowVersionMapper);
        DatasourceClusterBindingImpactView impact = fixture.service.previewDatasourceBindingImpact(99L, List.of());

        assertTrue(impact.getAffectedResources().stream().anyMatch(item ->
                StudioConstants.RESOURCE_TYPE_WORKFLOW.equals(item.getResourceType())
                        && Long.valueOf(401L).equals(item.getResourceId())));
    }

    @Test
    void shouldNotTreatSingleClusterAsImplicitlyAuthorized() {
        RuntimeValidationMapper validationMapper = mock(RuntimeValidationMapper.class);
        DatasourceClusterBindingMapper bindingMapper = mock(DatasourceClusterBindingMapper.class);
        CollectionTaskDefinitionMapper collectionMapper = mock(CollectionTaskDefinitionMapper.class);
        CollectionTaskDefinitionEntity collection = new CollectionTaskDefinitionEntity();
        collection.setId(301L);
        collection.setTenantId("tenant-a");
        collection.setProjectId(100L);
        collection.setRuntimeClusterId(10L);
        when(collectionMapper.selectList(any())).thenReturn(List.of(collection));
        when(collectionMapper.selectById(301L)).thenReturn(collection);

        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeClusterEntity cluster = new RuntimeClusterEntity();
        cluster.setId(10L);
        cluster.setTenantId("tenant-a");
        cluster.setEnabled(1);
        when(clusterMapper.selectOne(any())).thenReturn(cluster);
        when(clusterMapper.selectCount(any())).thenReturn(1L);
        ProjectRuntimeClusterMapper authorizationMapper = mock(ProjectRuntimeClusterMapper.class);
        when(authorizationMapper.selectCount(any())).thenReturn(0L);

        Fixture fixture = fixture(validationMapper, bindingMapper, collectionMapper);
        fixture.service.setRuntimePlacementMappers(clusterMapper, authorizationMapper);

        fixture.service.revalidatePlacementScope("tenant-a", 100L, 10L);

        ArgumentCaptor<RuntimeValidationEntity> captor = ArgumentCaptor.forClass(RuntimeValidationEntity.class);
        verify(validationMapper).insert(captor.capture());
        assertEquals(0, captor.getValue().getValid());
        assertEquals(RuntimeValidationService.ISSUE_PROJECT_CLUSTER_UNAUTHORIZED,
                captor.getValue().getIssueCode());
    }

    private Fixture fixture(RuntimeValidationMapper validationMapper, DatasourceClusterBindingMapper bindingMapper,
                            CollectionTaskDefinitionMapper collectionMapper) {
        return fixture(validationMapper, bindingMapper, collectionMapper,
                mock(WorkflowDefinitionMapper.class), mock(WorkflowVersionMapper.class));
    }

    private Fixture fixture(RuntimeValidationMapper validationMapper, DatasourceClusterBindingMapper bindingMapper,
                            CollectionTaskDefinitionMapper collectionMapper,
                            WorkflowDefinitionMapper workflowMapper,
                            WorkflowVersionMapper workflowVersionMapper) {
        QualityTaskDefinitionMapper qualityMapper = mock(QualityTaskDefinitionMapper.class);
        DataDevelopmentScriptMapper scriptMapper = mock(DataDevelopmentScriptMapper.class);
        DataServiceDefinitionMapper serviceMapper = mock(DataServiceDefinitionMapper.class);
        DataIngestionServiceMapper ingestionMapper = mock(DataIngestionServiceMapper.class);
        ProtocolConversionServiceMapper protocolMapper = mock(ProtocolConversionServiceMapper.class);
        when(qualityMapper.selectList(any())).thenReturn(List.of());
        when(scriptMapper.selectList(any())).thenReturn(List.of());
        when(serviceMapper.selectList(any())).thenReturn(List.of());
        when(ingestionMapper.selectList(any())).thenReturn(List.of());
        when(protocolMapper.selectList(any())).thenReturn(List.of());
        when(validationMapper.selectOne(any())).thenReturn(null);
        when(validationMapper.selectList(any())).thenReturn(List.of());
        StudioSecurityService security = mock(StudioSecurityService.class);
        ProjectResourceAccessService access = mock(ProjectResourceAccessService.class);
        when(security.currentTenantId()).thenReturn("tenant-a");
        when(access.requireCurrentProjectId()).thenReturn(100L);
        RuntimeValidationService service = new RuntimeValidationService(validationMapper, bindingMapper,
                collectionMapper, qualityMapper,
                workflowMapper, workflowVersionMapper, scriptMapper, serviceMapper, ingestionMapper,
                protocolMapper, security, access);
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        RuntimeClusterEntity cluster = new RuntimeClusterEntity();
        cluster.setEnabled(1);
        when(clusterMapper.selectOne(any())).thenReturn(cluster);
        ProjectRuntimeClusterMapper authorizationMapper = mock(ProjectRuntimeClusterMapper.class);
        when(authorizationMapper.selectCount(any())).thenReturn(1L);
        service.setRuntimePlacementMappers(clusterMapper, authorizationMapper);
        return new Fixture(service);
    }

    private DatasourceClusterBindingEntity binding(Long datasourceId, Long runtimeClusterId) {
        DatasourceClusterBindingEntity entity = new DatasourceClusterBindingEntity();
        entity.setDatasourceId(datasourceId);
        entity.setRuntimeClusterId(runtimeClusterId);
        entity.setEnabled(1);
        return entity;
    }

    private static class Fixture {
        private final RuntimeValidationService service;
        private Fixture(RuntimeValidationService service) { this.service = service; }
    }
}
