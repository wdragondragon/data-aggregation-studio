package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.UnstructuredAclPermission;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.request.FileTransferTaskSaveRequest;
import com.jdragon.studio.infra.entity.FileTransferTaskDefinitionEntity;
import com.jdragon.studio.infra.mapper.FileTransferTaskDefinitionMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileTransferTaskExpressionTest {

    @Test
    void acceptsDynamicFunctionAndDefersDynamicRegexCompilation() {
        Fixture fixture = fixture();
        FileTransferTaskSaveRequest request = request();
        request.getSelection().put("rootPath", "/incoming/$getCurrentTime('yyyyMMdd', '-1d')");
        request.getSelection().put("includeRegex", ".*/report_$getCurrentTime('yyyyMMdd')\\.txt$");

        fixture.service.save(request);

        verify(fixture.mapper).insert(any(FileTransferTaskDefinitionEntity.class));
        verify(fixture.permissionService).assertPermission(
                11L, 21L, "/incoming", UnstructuredAclPermission.DOWNLOAD);
    }

    @Test
    void rejectsUnknownFunctionWithFieldName() {
        Fixture fixture = fixture();
        FileTransferTaskSaveRequest request = request();
        request.getSelection().put("rootPath", "/incoming/$unknown('x')");

        assertThatThrownBy(() -> fixture.service.save(request))
                .isInstanceOf(StudioException.class)
                .hasMessageContaining("selection.rootPath")
                .hasMessageContaining("unknown");
        verify(fixture.mapper, never()).insert(any(FileTransferTaskDefinitionEntity.class));
    }

    @Test
    void rejectsPerFileTemplateOnSourceFields() {
        Fixture fixture = fixture();
        FileTransferTaskSaveRequest request = request();
        request.getSelection().put("paths", List.of("${fileName}"));

        assertThatThrownBy(() -> fixture.service.save(request))
                .isInstanceOf(StudioException.class)
                .hasMessageContaining("selection.paths[0]")
                .hasMessageContaining("only supported in mapping.targetPathTemplate");
    }

    @Test
    void rejectsUnknownTemplateVariable() {
        Fixture fixture = fixture();
        FileTransferTaskSaveRequest request = request();
        request.getMapping().put("targetRootPath", "/archive/${unknown}");

        assertThatThrownBy(() -> fixture.service.save(request))
                .isInstanceOf(StudioException.class)
                .hasMessageContaining("mapping.targetRootPath")
                .hasMessageContaining("${unknown}");
    }

    @Test
    void rejectsStaticInvalidRegex() {
        Fixture fixture = fixture();
        FileTransferTaskSaveRequest request = request();
        request.getSelection().put("includeRegex", "[broken");

        assertThatThrownBy(() -> fixture.service.save(request))
                .isInstanceOf(StudioException.class)
                .hasMessageContaining("Invalid regular expression in selection.includeRegex");
    }

    @Test
    void rejectsUnclosedTemplate() {
        Fixture fixture = fixture();
        FileTransferTaskSaveRequest request = request();
        request.getMapping().put("targetPathTemplate", "${fileName");

        assertThatThrownBy(() -> fixture.service.save(request))
                .isInstanceOf(StudioException.class)
                .hasMessageContaining("Invalid template in mapping.targetPathTemplate");
    }

    @Test
    void rejectsEmptyTemplateVariableAtSaveTime() {
        Fixture fixture = fixture();
        FileTransferTaskSaveRequest request = request();
        request.getMapping().put("targetPathTemplate", "${}");

        assertThatThrownBy(() -> fixture.service.save(request))
                .isInstanceOf(StudioException.class)
                .hasMessageContaining("mapping.targetPathTemplate")
                .hasMessageContaining("template variable name is required");
        verify(fixture.mapper, never()).insert(any(FileTransferTaskDefinitionEntity.class));
    }

    @Test
    void derivesStableAclPrefixesBeforeDynamicPathSegments() {
        assertThat(FileTransferTaskService.stablePathPrefix("/incoming/$getCurrentTime('yyyyMMdd')"))
                .isEqualTo("/incoming");
        assertThat(FileTransferTaskService.stablePathPrefix("/archive/${runId}"))
                .isEqualTo("/archive");
        assertThat(FileTransferTaskService.stablePathPrefix("/$getCurrentTime('yyyyMMdd')"))
                .isEqualTo("/");
        assertThat(FileTransferTaskService.stablePathPrefix("$getCurrentTime('yyyyMMdd')"))
                .isNull();
        assertThat(FileTransferTaskService.stablePathPrefix("/static/root"))
                .isEqualTo("/static/root");
    }

    private Fixture fixture() {
        FileTransferTaskDefinitionMapper mapper = mock(FileTransferTaskDefinitionMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        RuntimeClusterSelectionService clusterSelection = mock(RuntimeClusterSelectionService.class);
        ProjectResourceAccessService projectAccess = mock(ProjectResourceAccessService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        UnstructuredManagementService permissionService = mock(UnstructuredManagementService.class);
        DatasourceTypeCapabilityService capabilityService = mock(DatasourceTypeCapabilityService.class);
        when(projectAccess.requireCurrentProjectId()).thenReturn(10L);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentUserId()).thenReturn(99L);
        when(mapper.selectList(any())).thenReturn(List.of());
        when(dataSourceService.getInternalForProject(10L, 21L)).thenReturn(datasource(21L, "source"));
        when(dataSourceService.getInternalForProject(10L, 22L)).thenReturn(datasource(22L, "target"));
        when(permissionService.assertPermission(any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        FileTransferTaskService service = new FileTransferTaskService(
                mapper, dataSourceService, clusterSelection, projectAccess, securityService,
                permissionService, capabilityService, new ObjectMapper());
        return new Fixture(service, mapper, permissionService);
    }

    private FileTransferTaskSaveRequest request() {
        FileTransferTaskSaveRequest request = new FileTransferTaskSaveRequest();
        request.setName("daily-files");
        request.setCode("daily_files");
        request.setRuntimeClusterId(11L);
        request.setSourceDatasourceId(21L);
        request.setTargetDatasourceId(22L);
        request.setSelection(new LinkedHashMap<>(Map.of("rootPath", "/source")));
        request.setMapping(new LinkedHashMap<>(Map.of("targetRootPath", "/target")));
        return request;
    }

    private DataSourceDefinition datasource(Long id, String name) {
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(id);
        datasource.setTenantId("default");
        datasource.setProjectId(10L);
        datasource.setName(name);
        datasource.setTypeCode("oss");
        return datasource;
    }

    private record Fixture(FileTransferTaskService service,
                           FileTransferTaskDefinitionMapper mapper,
                           UnstructuredManagementService permissionService) {
    }
}
