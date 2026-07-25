package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeClusterHeartbeatServiceTest {

    @Test
    void shouldRetryClusterLockAndMergeLatestStoredInstanceSummary() {
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        ClusterLockService clusterLockService = mock(ClusterLockService.class);
        RuntimeClusterEntity initialLookup = cluster(50L);
        RuntimeClusterEntity lockedReload = cluster(50L);
        lockedReload.getInstancesJson().put("instance-a", Map.of(
                "instanceId", "instance-a",
                "heartbeatAt", LocalDateTime.now().minusSeconds(1).toString()));
        when(clusterMapper.selectOne(any())).thenReturn(initialLookup, lockedReload);
        when(clusterLockService.tryAcquireNonReentrant(
                "runtime-cluster-heartbeat:tenant-a:50", 10L)).thenReturn(false, true);
        when(clusterMapper.update(any(RuntimeClusterEntity.class), any(LambdaUpdateWrapper.class))).thenReturn(1);
        RuntimeClusterHeartbeatService service = new RuntimeClusterHeartbeatService(
                clusterMapper, clusterLockService);
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        attributes.put("bootId", "boot-b");
        attributes.put("workerGroupCode", "group-b");

        service.recordByCode("tenant-a", "C50", "instance-b", attributes,
                "1.0.0", "http://worker-b", now);

        ArgumentCaptor<RuntimeClusterEntity> updated = ArgumentCaptor.forClass(RuntimeClusterEntity.class);
        verify(clusterMapper).update(updated.capture(), any(LambdaUpdateWrapper.class));
        assertEquals(2, updated.getValue().getInstancesJson().size());
        assertTrue(updated.getValue().getInstancesJson().containsKey("instance-a"));
        assertEquals("boot-b", ((Map<?, ?>) updated.getValue().getInstancesJson().get("instance-b")).get("bootId"));
        assertEquals(now, updated.getValue().getLastHeartbeatAt());
        assertEquals("ONLINE", updated.getValue().getStatus());
        verify(clusterLockService, times(2)).tryAcquireNonReentrant(
                "runtime-cluster-heartbeat:tenant-a:50", 10L);
        verify(clusterLockService).release("runtime-cluster-heartbeat:tenant-a:50");
    }

    private RuntimeClusterEntity cluster(Long id) {
        RuntimeClusterEntity cluster = new RuntimeClusterEntity();
        cluster.setId(id);
        cluster.setTenantId("tenant-a");
        cluster.setCode("C50");
        cluster.setEnabled(1);
        cluster.setInstancesJson(new LinkedHashMap<String, Object>());
        return cluster;
    }
}
