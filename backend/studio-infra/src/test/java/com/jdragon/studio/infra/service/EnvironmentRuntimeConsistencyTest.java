package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.entity.EnvironmentDependencyEntity;
import com.jdragon.studio.infra.entity.ScriptEnvironmentDependencyRelEntity;
import com.jdragon.studio.infra.entity.ScriptEnvironmentEntity;
import com.jdragon.studio.infra.mapper.EnvironmentDependencyFileMapper;
import com.jdragon.studio.infra.mapper.EnvironmentDependencyMapper;
import com.jdragon.studio.infra.mapper.ScriptEnvironmentDependencyRelMapper;
import com.jdragon.studio.infra.mapper.ScriptEnvironmentMapper;
import com.jdragon.studio.infra.script.java.JavaDataScript;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnvironmentRuntimeConsistencyTest {

    @Mock
    private EnvironmentDependencyMapper dependencyMapper;
    @Mock
    private EnvironmentDependencyFileMapper dependencyFileMapper;
    @Mock
    private ScriptEnvironmentDependencyRelMapper relationMapper;
    @Mock
    private ScriptEnvironmentMapper environmentMapper;
    @Mock
    private StudioSecurityService securityService;
    @Mock
    private ObjectProvider<ScriptEnvironmentRuntimeService> runtimeServiceProvider;
    @Mock
    private ScriptEnvironmentRuntimeService runtimeService;
    @Mock
    private CloudObjectStorageService cloudObjectStorageService;
    @Mock
    private ArtifactRepositoryPublisher artifactRepositoryPublisher;
    @Mock
    private PythonPackageDownloadCountService pythonPackageDownloadCountService;

    @AfterEach
    void tearDown() {
        JavaDataDevelopmentExecutor.clearCompiledCache();
    }

    @Test
    void dependencyChangeBumpsReferencingEnvironmentVersion() {
        EnvironmentDependencyEntity dependency = dependency(10L);
        ScriptEnvironmentDependencyRelEntity relation = relation(20L, 10L);
        ScriptEnvironmentEntity environment = environment(20L, 3L);
        when(securityService.currentTenantId()).thenReturn("tenant-a");
        when(securityService.hasAnyRole(any(String[].class))).thenReturn(true);
        when(dependencyMapper.selectById(10L)).thenReturn(dependency);
        when(relationMapper.selectList(any())).thenReturn(Collections.singletonList(relation));
        when(environmentMapper.selectById(20L)).thenReturn(environment);
        when(runtimeServiceProvider.getIfAvailable()).thenReturn(runtimeService);
        when(dependencyFileMapper.selectList(any())).thenReturn(Collections.emptyList());

        EnvironmentDependencyService service = new EnvironmentDependencyService(
                dependencyMapper,
                dependencyFileMapper,
                relationMapper,
                environmentMapper,
                securityService,
                runtimeServiceProvider,
                cloudObjectStorageService,
                artifactRepositoryPublisher,
                pythonPackageDownloadCountService);

        service.disable(10L);

        ArgumentCaptor<ScriptEnvironmentEntity> environmentCaptor = ArgumentCaptor.forClass(ScriptEnvironmentEntity.class);
        verify(environmentMapper).updateById(environmentCaptor.capture());
        assertThat(environmentCaptor.getValue().getEnvironmentVersion()).isEqualTo(4L);
        verify(runtimeService).clearEnvironment(20L);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void compiledCacheCanBeClearedByEnvironmentVersion() throws Exception {
        Field field = JavaDataDevelopmentExecutor.class.getDeclaredField("COMPILED_CACHE");
        field.setAccessible(true);
        Map cache = (Map) field.get(null);
        cache.clear();
        cache.put("101:20:3:aaa", JavaDataScript.class);
        cache.put("101:20:4:bbb", JavaDataScript.class);
        cache.put("101:21:3:ccc", JavaDataScript.class);

        JavaDataDevelopmentExecutor.clearCompiledCache(20L, 3L);

        assertThat(cache).doesNotContainKey("101:20:3:aaa");
        assertThat(cache).containsKeys("101:20:4:bbb", "101:21:3:ccc");
    }

    private EnvironmentDependencyEntity dependency(Long id) {
        EnvironmentDependencyEntity entity = new EnvironmentDependencyEntity();
        entity.setId(id);
        entity.setTenantId("tenant-a");
        entity.setName("dep");
        entity.setScriptType("JAVA");
        entity.setEnabled(Integer.valueOf(1));
        return entity;
    }

    private ScriptEnvironmentDependencyRelEntity relation(Long environmentId, Long dependencyId) {
        ScriptEnvironmentDependencyRelEntity entity = new ScriptEnvironmentDependencyRelEntity();
        entity.setId(30L);
        entity.setTenantId("tenant-a");
        entity.setEnvironmentId(environmentId);
        entity.setDependencyId(dependencyId);
        return entity;
    }

    private ScriptEnvironmentEntity environment(Long id, Long version) {
        ScriptEnvironmentEntity entity = new ScriptEnvironmentEntity();
        entity.setId(id);
        entity.setTenantId("tenant-a");
        entity.setEnvironmentName("env");
        entity.setEnvironmentCode("env");
        entity.setEnabled(Integer.valueOf(1));
        entity.setEnvironmentVersion(version);
        return entity;
    }
}
