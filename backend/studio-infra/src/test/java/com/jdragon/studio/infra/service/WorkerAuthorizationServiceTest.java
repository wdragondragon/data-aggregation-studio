package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.infra.entity.ProjectRuntimeClusterEntity;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.mapper.ProjectRuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.ProjectWorkerBindingMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerAuthorizationServiceTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(RuntimeClusterEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                    RuntimeClusterEntity.class);
        }
        if (TableInfoHelper.getTableInfo(ProjectRuntimeClusterEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                    ProjectRuntimeClusterEntity.class);
        }
    }

    @Test
    void shouldRequireEnabledTenantClusterAndEnabledProjectAuthorization() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        ProjectRuntimeClusterMapper authorizationMapper = mock(ProjectRuntimeClusterMapper.class);
        WorkerAuthorizationService service = new WorkerAuthorizationService(
                mock(ProjectWorkerBindingMapper.class), mock(WorkerLeaseMapper.class),
                clusterMapper, authorizationMapper);
        when(clusterMapper.selectCount(any())).thenReturn(1L);
        when(authorizationMapper.selectCount(any())).thenReturn(1L);

        assertTrue(service.isRuntimeClusterAuthorizedForProject("tenant-a", 10L, 50L));

        ArgumentCaptor<LambdaQueryWrapper<RuntimeClusterEntity>> clusterQuery =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(clusterMapper).selectCount(clusterQuery.capture());
        assertTrue(clusterQuery.getValue().getSqlSegment().contains("tenant_id"));
        assertTrue(clusterQuery.getValue().getSqlSegment().contains("enabled"));

        ArgumentCaptor<LambdaQueryWrapper<ProjectRuntimeClusterEntity>> authorizationQuery =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(authorizationMapper).selectCount(authorizationQuery.capture());
        assertTrue(authorizationQuery.getValue().getSqlSegment().contains("project_id"));
        assertTrue(authorizationQuery.getValue().getSqlSegment().contains("runtime_cluster_id"));
        assertTrue(authorizationQuery.getValue().getSqlSegment().contains("enabled"));
    }

    @Test
    void shouldRejectWhenClusterOrProjectAuthorizationIsMissing() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        ProjectRuntimeClusterMapper authorizationMapper = mock(ProjectRuntimeClusterMapper.class);
        WorkerAuthorizationService service = new WorkerAuthorizationService(
                mock(ProjectWorkerBindingMapper.class), mock(WorkerLeaseMapper.class),
                clusterMapper, authorizationMapper);

        when(clusterMapper.selectCount(any())).thenReturn(0L);
        assertFalse(service.isRuntimeClusterAuthorizedForProject("tenant-a", 10L, 50L));

        when(clusterMapper.selectCount(any())).thenReturn(1L);
        when(authorizationMapper.selectCount(any())).thenReturn(0L);
        assertFalse(service.isRuntimeClusterAuthorizedForProject("tenant-a", 10L, 50L));
    }
}
