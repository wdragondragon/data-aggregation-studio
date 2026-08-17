package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.enums.UnstructuredAclPermission;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.DataSourceOptionView;
import com.jdragon.studio.dto.model.UnstructuredSourceView;
import com.jdragon.studio.infra.service.unstructured.UnstructuredAclService;
import com.jdragon.studio.infra.service.unstructured.UnstructuredAuditService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnstructuredManagementSourcesTest {

    @Test
    void listsFileDatasourcesSelectedByRuntimeCapability() {
        DataSourceService dataSourceService = mock(DataSourceService.class);
        RuntimeClusterSelectionService clusterSelectionService =
                mock(RuntimeClusterSelectionService.class);
        ProjectResourceAccessService projectAccess = mock(ProjectResourceAccessService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        RuntimeDatasourceProbeRouter runtimeRouter = mock(RuntimeDatasourceProbeRouter.class);
        DatasourceTypeCapabilityService capabilityService =
                mock(DatasourceTypeCapabilityService.class);
        UnstructuredAclService aclService = mock(UnstructuredAclService.class);
        UnstructuredAuditService auditService = mock(UnstructuredAuditService.class);
        UnstructuredManagementService service = new UnstructuredManagementService(
                dataSourceService, clusterSelectionService, projectAccess, securityService,
                runtimeRouter, capabilityService, aclService, auditService);

        long projectId = 10L;
        long runtimeClusterId = 20L;
        long datasourceId = 30L;
        Set<String> browseTypes = Set.of("local", "ftp", "sftp", "minio", "oss");
        DataSourceOptionView option = new DataSourceOptionView();
        option.setId(datasourceId);
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(datasourceId);
        datasource.setTenantId("default");
        datasource.setProjectId(projectId);
        datasource.setName("FTP source");
        datasource.setTypeCode("ftp");

        when(projectAccess.requireCurrentProjectId()).thenReturn(projectId);
        when(clusterSelectionService.resolveForSave(projectId, runtimeClusterId))
                .thenReturn(runtimeClusterId);
        when(capabilityService.typesWithRuntimeCapability("browse", true))
                .thenReturn(browseTypes);
        when(dataSourceService.listBasicOptionsByTypes(browseTypes, runtimeClusterId))
                .thenReturn(List.of(option));
        when(dataSourceService.get(datasourceId)).thenReturn(datasource);
        when(capabilityService.hasRuntimeCapability("ftp", "browse")).thenReturn(true);
        when(aclService.hasPermission(eq(datasource), eq("/"), any()))
                .thenAnswer(invocation -> invocation.getArgument(2)
                        == UnstructuredAclPermission.BROWSE);

        List<UnstructuredSourceView> result = service.sources(runtimeClusterId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(datasourceId);
        assertThat(result.get(0).getTypeCode()).isEqualTo("ftp");
        assertThat(result.get(0).getEffectivePermissions()).containsExactly("BROWSE");
        verify(dataSourceService).listBasicOptionsByTypes(browseTypes, runtimeClusterId);
    }
}
