package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.model.ScriptEnvironmentView;
import com.jdragon.studio.dto.model.request.ScriptEnvironmentSaveRequest;
import com.jdragon.studio.infra.entity.ArtifactStoreEntity;
import com.jdragon.studio.infra.entity.EnvironmentDependencyEntity;
import com.jdragon.studio.infra.entity.ScriptEnvironmentDependencyRelEntity;
import com.jdragon.studio.infra.entity.ScriptEnvironmentEntity;
import com.jdragon.studio.infra.mapper.EnvironmentDependencyMapper;
import com.jdragon.studio.infra.mapper.ScriptEnvironmentDependencyRelMapper;
import com.jdragon.studio.infra.mapper.ScriptEnvironmentMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScriptEnvironmentRelationSyncTest {

    @Test
    void shouldReactivateExistingRelationAndInsertOnlyNewDependency() {
        ScriptEnvironmentMapper environmentMapper = mock(ScriptEnvironmentMapper.class);
        ScriptEnvironmentDependencyRelMapper relationMapper = mock(ScriptEnvironmentDependencyRelMapper.class);
        EnvironmentDependencyMapper dependencyMapper = mock(EnvironmentDependencyMapper.class);
        EnvironmentDependencyService dependencyService = mock(EnvironmentDependencyService.class);
        ArtifactStoreService artifactStoreService = mock(ArtifactStoreService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ScriptEnvironmentRuntimeService> runtimeServiceProvider = mock(ObjectProvider.class);
        ScriptEnvironmentService service = new ScriptEnvironmentService(
                environmentMapper,
                relationMapper,
                dependencyMapper,
                dependencyService,
                artifactStoreService,
                securityService,
                runtimeServiceProvider);

        ScriptEnvironmentEntity environment = new ScriptEnvironmentEntity();
        environment.setId(10L);
        environment.setTenantId("default");
        environment.setEnvironmentName("Python live");
        environment.setEnvironmentCode("python-live");
        environment.setEnabled(1);
        environment.setUseApplicationParent(0);
        environment.setEnvironmentVersion(1L);
        environment.setPythonInstallMode(ScriptEnvironmentService.PYTHON_INSTALL_MODE_PYPI_LIVE);
        environment.setPythonRepositoryId(20L);

        ArtifactStoreEntity repository = new ArtifactStoreEntity();
        repository.setId(20L);
        repository.setProvider("PYPI");
        repository.setSimpleIndexUrl("http://127.0.0.1:8080/simple/");

        EnvironmentDependencyEntity existingDependency = pythonDependency(101L, "existing-package", "1.0.0");
        EnvironmentDependencyEntity newDependency = pythonDependency(102L, "numpy", "2.5.1");

        ScriptEnvironmentDependencyRelEntity existingRelation = relation(10L, 101L, 1);
        ScriptEnvironmentDependencyRelEntity newRelation = relation(10L, 102L, 2);

        when(securityService.hasAnyRole(any(String[].class))).thenReturn(true);
        when(securityService.currentTenantId()).thenReturn("default");
        when(environmentMapper.selectById(10L)).thenReturn(environment);
        when(environmentMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(artifactStoreService.requireEnabled(20L)).thenReturn(repository);
        when(dependencyService.requireEnabledDependency(101L)).thenReturn(existingDependency);
        when(dependencyService.requireEnabledDependency(102L)).thenReturn(newDependency);
        when(relationMapper.reactivateOrUpdate("default", 10L, 101L, 1)).thenReturn(1);
        when(relationMapper.reactivateOrUpdate("default", 10L, 102L, 2)).thenReturn(0);
        when(relationMapper.selectList(any())).thenReturn(Arrays.asList(existingRelation, newRelation));
        when(dependencyMapper.selectById(101L)).thenReturn(existingDependency);
        when(dependencyMapper.selectById(102L)).thenReturn(newDependency);
        when(runtimeServiceProvider.getIfAvailable()).thenReturn(null);

        ScriptEnvironmentSaveRequest request = new ScriptEnvironmentSaveRequest();
        request.setId(10L);
        request.setEnvironmentName("Python live");
        request.setEnvironmentCode("python-live");
        request.setEnabled(true);
        request.setUseApplicationParent(false);
        request.setPythonInstallMode(ScriptEnvironmentService.PYTHON_INSTALL_MODE_PYPI_LIVE);
        request.setPythonRepositoryId(20L);
        request.setDependencyIds(Arrays.asList(101L, 102L));

        ScriptEnvironmentView result = service.saveOrUpdateCheck(request);

        assertEquals(2L, result.getEnvironmentVersion());
        assertEquals(Arrays.asList(101L, 102L), result.getDependencyIds());
        verify(relationMapper).softDeleteActiveByEnvironment("default", 10L);
        verify(relationMapper).reactivateOrUpdate("default", 10L, 101L, 1);
        verify(relationMapper).reactivateOrUpdate("default", 10L, 102L, 2);

        ArgumentCaptor<ScriptEnvironmentDependencyRelEntity> inserted =
                ArgumentCaptor.forClass(ScriptEnvironmentDependencyRelEntity.class);
        verify(relationMapper).insert(inserted.capture());
        assertEquals(102L, inserted.getValue().getDependencyId());
        verify(relationMapper, never()).delete(any());
    }

    private EnvironmentDependencyEntity pythonDependency(Long id, String name, String version) {
        EnvironmentDependencyEntity dependency = new EnvironmentDependencyEntity();
        dependency.setId(id);
        dependency.setTenantId("default");
        dependency.setName(name);
        dependency.setVersion(version);
        dependency.setScriptType("PYTHON");
        dependency.setEnabled(1);
        return dependency;
    }

    private ScriptEnvironmentDependencyRelEntity relation(Long environmentId,
                                                          Long dependencyId,
                                                          int sortOrder) {
        ScriptEnvironmentDependencyRelEntity relation = new ScriptEnvironmentDependencyRelEntity();
        relation.setTenantId("default");
        relation.setEnvironmentId(environmentId);
        relation.setDependencyId(dependencyId);
        relation.setSortOrder(sortOrder);
        return relation;
    }
}
