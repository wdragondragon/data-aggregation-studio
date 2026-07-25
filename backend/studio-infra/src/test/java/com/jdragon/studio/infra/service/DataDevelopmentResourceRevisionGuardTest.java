package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.ScriptType;
import com.jdragon.studio.dto.model.DataScriptExecutionResultView;
import com.jdragon.studio.infra.entity.DataDevelopmentScriptEntity;
import com.jdragon.studio.infra.mapper.DataDevelopmentDirectoryMapper;
import com.jdragon.studio.infra.mapper.DataDevelopmentScriptMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataDevelopmentResourceRevisionGuardTest {

    @Test
    void controlPlaneShouldQueueSavedScriptWithoutLocalExecutor() {
        DataDevelopmentScriptMapper scriptMapper = mock(DataDevelopmentScriptMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService projectResourceAccessService = mock(ProjectResourceAccessService.class);
        DataDevelopmentWorkerExecutionService workerExecutionService =
                mock(DataDevelopmentWorkerExecutionService.class);
        DataDevelopmentService service = new DataDevelopmentService(
                mock(DataDevelopmentDirectoryMapper.class),
                scriptMapper,
                mock(DataSourceService.class),
                mock(DatasourceTypeCapabilityService.class),
                securityService,
                projectResourceAccessService,
                mock(ScriptEnvironmentService.class),
                workerExecutionService);

        DataDevelopmentScriptEntity script = new DataDevelopmentScriptEntity();
        script.setId(102L);
        script.setTenantId("default");
        script.setProjectId(10L);
        script.setRuntimeClusterId(46L);
        script.setFileName("customer-risk.py");
        script.setScriptType(ScriptType.PYTHON.name());
        when(scriptMapper.selectById(102L)).thenReturn(script);
        when(securityService.currentTenantId()).thenReturn("default");
        when(projectResourceAccessService.requireCurrentProjectId()).thenReturn(10L);
        DataScriptExecutionResultView expected = new DataScriptExecutionResultView();
        when(workerExecutionService.executeSavedScript(
                script, ScriptType.PYTHON, 46L,
                Collections.emptyMap(), Collections.emptyMap(), null, null)).thenReturn(expected);

        assertThat(service.executeSavedScript(102L, null)).isSameAs(expected);
        verify(workerExecutionService).executeSavedScript(
                script, ScriptType.PYTHON, 46L,
                Collections.emptyMap(), Collections.emptyMap(), null, null);
    }

    @Test
    void shouldRejectQueuedSavedScriptWhenDependencyRevisionChanged() {
        DataDevelopmentScriptMapper scriptMapper = mock(DataDevelopmentScriptMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService projectResourceAccessService = mock(ProjectResourceAccessService.class);
        DataDevelopmentService service = new DataDevelopmentService(
                mock(DataDevelopmentDirectoryMapper.class),
                scriptMapper,
                mock(DataSourceService.class),
                mock(DatasourceTypeCapabilityService.class),
                securityService,
                projectResourceAccessService,
                mock(ScriptEnvironmentService.class),
                mock(DataDevelopmentWorkerExecutionService.class));
        RuntimeResourceRevisionService revisionService = mock(RuntimeResourceRevisionService.class);
        service.setRuntimeResourceRevisionService(revisionService);

        DataDevelopmentScriptEntity script = new DataDevelopmentScriptEntity();
        script.setId(101L);
        script.setTenantId("default");
        script.setProjectId(10L);
        script.setFileName("customer-risk.java");
        script.setScriptType(ScriptType.JAVA.name());
        script.setUpdatedAt(LocalDateTime.of(2026, 7, 20, 9, 0));
        when(scriptMapper.selectById(101L)).thenReturn(script);
        when(securityService.currentTenantId()).thenReturn("default");
        when(projectResourceAccessService.requireCurrentProjectId()).thenReturn(10L);
        when(revisionService.scriptRevision(101L)).thenReturn("current-composite-revision");

        Map<String, Object> runtimeContext = new LinkedHashMap<String, Object>();
        runtimeContext.put("resourceRevision", "queued-composite-revision");

        assertThatThrownBy(() -> service.executeScript(101L, null,
                Collections.emptyMap(), runtimeContext, Collections.emptyMap()))
                .isInstanceOf(StudioException.class)
                .hasMessageContaining("Script configuration changed after dispatch");
    }
}
