package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.system.SystemProjectWorkerOptionView;
import com.jdragon.studio.dto.model.system.SystemProjectWorkerView;
import com.jdragon.studio.dto.model.system.SystemWorkerInstanceView;
import com.jdragon.studio.infra.entity.ProjectEntity;
import com.jdragon.studio.infra.entity.ProjectWorkerBindingEntity;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.DataDevelopmentScriptMapper;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DataServiceDefinitionMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.mapper.ProjectMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberMapper;
import com.jdragon.studio.infra.mapper.ProjectMemberRequestMapper;
import com.jdragon.studio.infra.mapper.ProjectWorkerBindingMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionServiceMapper;
import com.jdragon.studio.infra.mapper.ResourceShareMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import com.jdragon.studio.infra.mapper.TenantMapper;
import com.jdragon.studio.infra.mapper.TenantMemberMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.service.NotificationService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import com.jdragon.studio.infra.service.SystemManagementService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemProjectWorkerViewRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        initTableInfo(WorkerLeaseEntity.class);
        initTableInfo(ProjectWorkerBindingEntity.class);
    }

    @Test
    void shouldGroupWorkerInstancesAndKeepOnlyRecentLeases() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ProjectWorkerBindingMapper bindingMapper = mock(ProjectWorkerBindingMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        SystemManagementService service = service(projectMapper, bindingMapper, workerLeaseMapper, securityService);

        ProjectEntity project = new ProjectEntity();
        project.setId(100L);
        project.setTenantId("default");
        when(projectMapper.selectById(100L)).thenReturn(project);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentRoleCodes()).thenReturn(Collections.singletonList(StudioConstants.ROLE_TENANT_ADMIN));

        LocalDateTime now = LocalDateTime.now();
        when(workerLeaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(
                lease("group-a", "pod-a", "instance-a", "ONLINE", now.minusSeconds(5), now.plusSeconds(25)),
                lease("group-a", "pod-b", "instance-b", "OFFLINE", now.minusHours(1), now.minusMinutes(59)),
                lease("group-b", "pod-c", "instance-c", "ONLINE", now.minusMinutes(2), now.minusSeconds(60)),
                lease("group-old", "pod-old", "instance-old", "OFFLINE", now.minusHours(25), now.minusHours(24))
        ));
        when(bindingMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Arrays.asList(
                binding(1L, "group-a", 1),
                binding(2L, "group-c", 1)
        ));

        List<SystemProjectWorkerView> views = service.listProjectWorkers(100L);

        assertEquals(3, views.size());
        SystemProjectWorkerView groupA = find(views, "group-a");
        assertTrue(Boolean.TRUE.equals(groupA.getBoundToProject()));
        assertEquals(Integer.valueOf(1), groupA.getOnlineInstanceCount());
        assertEquals(Integer.valueOf(2), groupA.getRecentInstanceCount());
        assertEquals("ONLINE", groupA.getDisplayStatus());
        assertEquals(2, groupA.getInstances().size());
        assertTrue(Boolean.TRUE.equals(findInstance(groupA, "instance-a").getOnline()));
        assertFalse(Boolean.TRUE.equals(findInstance(groupA, "instance-b").getOnline()));

        SystemProjectWorkerView groupB = find(views, "group-b");
        assertFalse(Boolean.TRUE.equals(groupB.getBoundToProject()));
        assertEquals(Integer.valueOf(0), groupB.getOnlineInstanceCount());
        assertEquals("OFFLINE", groupB.getDisplayStatus());
        assertFalse(Boolean.TRUE.equals(groupB.getInstances().get(0).getOnline()));

        SystemProjectWorkerView groupC = find(views, "group-c");
        assertTrue(Boolean.TRUE.equals(groupC.getBoundToProject()));
        assertTrue(Boolean.TRUE.equals(groupC.getEnabled()));
        assertEquals(Integer.valueOf(0), groupC.getOnlineInstanceCount());
        assertEquals(Integer.valueOf(0), groupC.getRecentInstanceCount());
        assertEquals("NO_INSTANCE", groupC.getDisplayStatus());
        assertTrue(groupC.getInstances().isEmpty());
        assertNull(findOrNull(views, "group-old"));
    }

    private SystemManagementService service(ProjectMapper projectMapper,
                                            ProjectWorkerBindingMapper bindingMapper,
                                            WorkerLeaseMapper workerLeaseMapper,
                                            StudioSecurityService securityService) {
        return new SystemManagementService(
                mock(TenantMapper.class),
                projectMapper,
                mock(TenantMemberMapper.class),
                mock(ProjectMemberMapper.class),
                mock(ProjectMemberRequestMapper.class),
                bindingMapper,
                mock(ResourceShareMapper.class),
                mock(StudioUserMapper.class),
                workerLeaseMapper,
                mock(DatasourceMapper.class),
                mock(DataModelMapper.class),
                mock(CollectionTaskDefinitionMapper.class),
                mock(WorkflowDefinitionMapper.class),
                mock(DataDevelopmentScriptMapper.class),
                mock(DataServiceDefinitionMapper.class),
                mock(DataIngestionServiceMapper.class),
                mock(ProtocolConversionServiceMapper.class),
                securityService,
                mock(NotificationService.class)
        );
    }

    private WorkerLeaseEntity lease(String groupCode,
                                    String workerCode,
                                    String instanceId,
                                    String status,
                                    LocalDateTime lastHeartbeatAt,
                                    LocalDateTime leaseExpiresAt) {
        WorkerLeaseEntity lease = new WorkerLeaseEntity();
        lease.setTenantId("default");
        lease.setWorkerGroupCode(groupCode);
        lease.setWorkerCode(workerCode);
        lease.setInstanceId(instanceId);
        lease.setWorkerKind("WORKER");
        lease.setPodName(workerCode);
        lease.setNodeName("node-a");
        lease.setHostName("host-a");
        lease.setStatus(status);
        lease.setLastHeartbeatAt(lastHeartbeatAt);
        lease.setLeaseExpiresAt(leaseExpiresAt);
        return lease;
    }

    private ProjectWorkerBindingEntity binding(Long id, String workerGroupCode, Integer enabled) {
        ProjectWorkerBindingEntity binding = new ProjectWorkerBindingEntity();
        binding.setId(id);
        binding.setTenantId("default");
        binding.setProjectId(100L);
        binding.setWorkerGroupCode(workerGroupCode);
        binding.setWorkerCode(workerGroupCode);
        binding.setEnabled(enabled);
        return binding;
    }

    private SystemProjectWorkerOptionView workerOption(String workerGroupCode,
                                                       int onlineInstanceCount,
                                                       int recentInstanceCount,
                                                       boolean boundToProject,
                                                       boolean enabled) {
        SystemProjectWorkerOptionView option = new SystemProjectWorkerOptionView();
        option.setWorkerGroupCode(workerGroupCode);
        option.setWorkerCode(workerGroupCode);
        option.setOnlineInstanceCount(onlineInstanceCount);
        option.setRecentInstanceCount(recentInstanceCount);
        option.setBoundToProject(boundToProject);
        option.setEnabled(enabled);
        return option;
    }

    private SystemProjectWorkerView find(List<SystemProjectWorkerView> views, String workerGroupCode) {
        SystemProjectWorkerView view = findOrNull(views, workerGroupCode);
        assertNotNull(view, workerGroupCode);
        return view;
    }

    private SystemProjectWorkerView findOrNull(List<SystemProjectWorkerView> views, String workerGroupCode) {
        for (SystemProjectWorkerView view : views) {
            if (workerGroupCode.equals(view.getWorkerGroupCode())) {
                return view;
            }
        }
        return null;
    }

    @Test
    void shouldKeepDisabledBindingAsBoundButDisabled() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ProjectWorkerBindingMapper bindingMapper = mock(ProjectWorkerBindingMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        SystemManagementService service = service(projectMapper, bindingMapper, workerLeaseMapper, securityService);

        ProjectEntity project = new ProjectEntity();
        project.setId(100L);
        project.setTenantId("default");
        when(projectMapper.selectById(100L)).thenReturn(project);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentRoleCodes()).thenReturn(Collections.singletonList(StudioConstants.ROLE_TENANT_ADMIN));
        when(workerLeaseMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        when(bindingMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(binding(3L, "group-disabled", 0)));

        SystemProjectWorkerView view = find(service.listProjectWorkers(100L), "group-disabled");

        assertTrue(Boolean.TRUE.equals(view.getBoundToProject()));
        assertFalse(Boolean.TRUE.equals(view.getEnabled()));
        assertEquals("NO_INSTANCE", view.getDisplayStatus());
    }

    @Test
    void workerPageShouldPageWorkerGroupsBeforeLoadingLeasesAndBindings() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ProjectWorkerBindingMapper bindingMapper = mock(ProjectWorkerBindingMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        SystemManagementService service = service(projectMapper, bindingMapper, workerLeaseMapper, securityService);

        ProjectEntity project = new ProjectEntity();
        project.setId(100L);
        project.setTenantId("default");
        when(projectMapper.selectById(100L)).thenReturn(project);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentRoleCodes()).thenReturn(Collections.singletonList(StudioConstants.ROLE_TENANT_ADMIN));
        when(workerLeaseMapper.countVisibleWorkerGroups(eq("default"), eq(100L), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(4L);
        when(workerLeaseMapper.selectVisibleWorkerGroupPage(eq("default"), eq(100L), any(LocalDateTime.class), any(LocalDateTime.class), eq(2), eq(2)))
                .thenReturn(Arrays.asList("group-b", "group-c"));
        LocalDateTime now = LocalDateTime.now();
        when(workerLeaseMapper.selectVisibleLeasesForGroups(eq("default"), any(LocalDateTime.class), any(LocalDateTime.class), eq(Arrays.asList("group-b", "group-c"))))
                .thenReturn(Collections.singletonList(lease("group-b", "pod-c", "instance-c", "ONLINE", now.minusMinutes(1), now.plusMinutes(1))));
        when(bindingMapper.selectForWorkerGroups(eq("default"), eq(100L), eq(Arrays.asList("group-b", "group-c"))))
                .thenReturn(Collections.singletonList(binding(2L, "group-c", 1)));

        PageView<SystemProjectWorkerView> page = service.listProjectWorkersPage(100L, 2, 2);

        assertEquals(2, page.getPageNo());
        assertEquals(2, page.getPageSize());
        assertEquals(4L, page.getTotal());
        assertEquals(2, page.getItems().size());
        assertEquals("group-b", page.getItems().get(0).getWorkerGroupCode());
        assertEquals("group-c", page.getItems().get(1).getWorkerGroupCode());
        assertFalse(Boolean.TRUE.equals(page.getItems().get(0).getBoundToProject()));
        assertTrue(Boolean.TRUE.equals(page.getItems().get(1).getBoundToProject()));
        verify(workerLeaseMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verify(bindingMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void workerOptionsShouldUseLightweightGroupProjectionOnly() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ProjectWorkerBindingMapper bindingMapper = mock(ProjectWorkerBindingMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        SystemManagementService service = service(projectMapper, bindingMapper, workerLeaseMapper, securityService);

        ProjectEntity project = new ProjectEntity();
        project.setId(100L);
        project.setTenantId("default");
        when(projectMapper.selectById(100L)).thenReturn(project);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentRoleCodes()).thenReturn(Collections.singletonList(StudioConstants.ROLE_TENANT_ADMIN));
        when(workerLeaseMapper.selectVisibleWorkerGroupOptions(eq("default"), eq(100L), any(LocalDateTime.class), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(Collections.singletonList(workerOption("数据采集Worker组", 2, 3, true, true)));

        List<SystemProjectWorkerOptionView> options = service.listProjectWorkerOptions(100L);

        assertEquals(1, options.size());
        SystemProjectWorkerOptionView option = options.get(0);
        assertEquals("数据采集Worker组", option.getWorkerGroupCode());
        assertEquals(Integer.valueOf(2), option.getOnlineInstanceCount());
        assertEquals(Integer.valueOf(3), option.getRecentInstanceCount());
        assertTrue(Boolean.TRUE.equals(option.getBoundToProject()));
        assertTrue(Boolean.TRUE.equals(option.getEnabled()));
        verify(workerLeaseMapper).selectVisibleWorkerGroupOptions(eq("default"), eq(100L), any(LocalDateTime.class), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(workerLeaseMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verify(workerLeaseMapper, never()).countVisibleWorkerGroups(eq("default"), eq(100L), any(LocalDateTime.class), any(LocalDateTime.class));
        verify(workerLeaseMapper, never()).selectVisibleWorkerGroupPage(eq("default"), eq(100L), any(LocalDateTime.class), any(LocalDateTime.class), anyInt(), anyInt());
        verify(workerLeaseMapper, never()).selectVisibleLeasesForGroups(eq("default"), any(LocalDateTime.class), any(LocalDateTime.class), anyList());
        verify(bindingMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verify(bindingMapper, never()).selectForWorkerGroups(eq("default"), eq(100L), anyList());
    }

    @Test
    void shouldReviveDeletedWorkerBindingWhenRebindingSameGroup() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ProjectWorkerBindingMapper bindingMapper = mock(ProjectWorkerBindingMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        SystemManagementService service = service(projectMapper, bindingMapper, workerLeaseMapper, securityService);

        ProjectEntity project = new ProjectEntity();
        project.setId(100L);
        project.setTenantId("default");
        when(projectMapper.selectById(100L)).thenReturn(project);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentRoleCodes()).thenReturn(Collections.singletonList(StudioConstants.ROLE_TENANT_ADMIN));
        ProjectWorkerBindingEntity deletedBinding = binding(4L, "group-rebind", 0);
        deletedBinding.setDeleted(1);
        when(bindingMapper.selectIncludingDeleted("default", 100L, "group-rebind")).thenReturn(deletedBinding);

        ProjectWorkerBindingEntity request = new ProjectWorkerBindingEntity();
        request.setProjectId(100L);
        request.setWorkerGroupCode("group-rebind");
        request.setEnabled(1);
        ProjectWorkerBindingEntity result = service.saveProjectWorkerBinding(request);

        verify(bindingMapper).reviveDeletedById(4L, "group-rebind", "group-rebind", 1);
        verify(bindingMapper, never()).insert(any(ProjectWorkerBindingEntity.class));
        assertEquals(Integer.valueOf(0), result.getDeleted());
        assertEquals(Integer.valueOf(1), result.getEnabled());
    }

    @Test
    void shouldRejectChangingWorkerGroupForExistingBinding() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ProjectWorkerBindingMapper bindingMapper = mock(ProjectWorkerBindingMapper.class);
        WorkerLeaseMapper workerLeaseMapper = mock(WorkerLeaseMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        SystemManagementService service = service(projectMapper, bindingMapper, workerLeaseMapper, securityService);

        ProjectEntity project = new ProjectEntity();
        project.setId(100L);
        project.setTenantId("default");
        when(projectMapper.selectById(100L)).thenReturn(project);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentRoleCodes()).thenReturn(Collections.singletonList(StudioConstants.ROLE_TENANT_ADMIN));
        when(bindingMapper.selectById(5L)).thenReturn(binding(5L, "group-a", 1));

        ProjectWorkerBindingEntity request = new ProjectWorkerBindingEntity();
        request.setId(5L);
        request.setProjectId(100L);
        request.setWorkerGroupCode("group-b");
        request.setEnabled(1);

        assertThrows(StudioException.class, () -> service.saveProjectWorkerBinding(request));
        verify(bindingMapper, never()).updateById(any(ProjectWorkerBindingEntity.class));
        verify(bindingMapper, never()).insert(any(ProjectWorkerBindingEntity.class));
    }

    private SystemWorkerInstanceView findInstance(SystemProjectWorkerView view, String workerInstanceId) {
        for (SystemWorkerInstanceView instance : view.getInstances()) {
            if (workerInstanceId.equals(instance.getWorkerInstanceId())) {
                return instance;
            }
        }
        throw new AssertionError(workerInstanceId);
    }

    private static void initTableInfo(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityType);
        }
    }
}
