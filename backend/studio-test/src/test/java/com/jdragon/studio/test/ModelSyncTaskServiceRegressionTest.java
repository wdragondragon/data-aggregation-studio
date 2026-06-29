package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jdragon.studio.dto.enums.ModelSyncTaskItemStatus;
import com.jdragon.studio.dto.enums.ModelSyncTaskStatus;
import com.jdragon.studio.dto.model.ModelSyncTaskItemView;
import com.jdragon.studio.dto.model.ModelSyncTaskView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.infra.entity.ModelSyncTaskEntity;
import com.jdragon.studio.infra.entity.ModelSyncTaskItemEntity;
import com.jdragon.studio.infra.mapper.ModelSyncTaskItemMapper;
import com.jdragon.studio.infra.mapper.ModelSyncTaskMapper;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataModelSyncBatchResult;
import com.jdragon.studio.infra.service.DataModelSyncItemResult;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.FollowSubscriptionService;
import com.jdragon.studio.infra.service.ModelSyncTaskService;
import com.jdragon.studio.infra.service.NotificationService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelSyncTaskServiceRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        initTableInfo(ModelSyncTaskEntity.class);
        initTableInfo(ModelSyncTaskItemEntity.class);
    }

    @Test
    void taskListShouldSelectOnlyVisibleSummaryFields() {
        ModelSyncTaskMapper taskMapper = mock(ModelSyncTaskMapper.class);
        ModelSyncTaskItemMapper itemMapper = mock(ModelSyncTaskItemMapper.class);
        ModelSyncTaskService service = modelSyncTaskService(taskMapper, itemMapper, mock(DataModelService.class));
        when(taskMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<ModelSyncTaskEntity> page = invocation.getArgument(0);
            page.setTotal(1L);
            page.setRecords(Collections.singletonList(taskSummary()));
            return page;
        });

        PageView<ModelSyncTaskView> page = service.list(1, 20, null, null, null);

        assertThat(page.getItems()).hasSize(1);
        ModelSyncTaskView item = page.getItems().get(0);
        assertThat(item.getName()).isEqualTo("长期回归-客户模型同步");
        assertThat(item.getStatus()).isEqualTo(ModelSyncTaskStatus.RUNNING);
        assertThat(item.getDatasourceNameSnapshot()).isNull();

        ArgumentCaptor<LambdaQueryWrapper<ModelSyncTaskEntity>> taskCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(taskMapper).selectPage(any(Page.class), taskCaptor.capture());
        assertThat(taskCaptor.getValue().getSqlSelect())
                .contains("id", "tenant_id", "project_id", "deleted", "created_at", "updated_at",
                        "name", "status", "total_count", "success_count", "failed_count",
                        "stopped_count", "progress_percent", "stop_requested", "duration_ms")
                .doesNotContain("datasource_id",
                        "datasource_type",
                        "datasource_name_snapshot",
                        "batch_no",
                        "source",
                        "created_by",
                        "started_at",
                        "finished_at",
                        "last_error");
    }

    @Test
    void taskItemsShouldUseLightTaskAccessAndSelectOnlyVisibleFields() {
        ModelSyncTaskMapper taskMapper = mock(ModelSyncTaskMapper.class);
        ModelSyncTaskItemMapper itemMapper = mock(ModelSyncTaskItemMapper.class);
        ModelSyncTaskService service = modelSyncTaskService(taskMapper, itemMapper, mock(DataModelService.class));
        ModelSyncTaskEntity task = task();
        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
        when(itemMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<ModelSyncTaskItemEntity> page = invocation.getArgument(0);
            page.setTotal(1L);
            page.setRecords(Collections.singletonList(taskItem(task.getId())));
            return page;
        });

        PageView<ModelSyncTaskItemView> page = service.listItems(task.getId(), 1, 20, "客户", "SUCCESS");

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getPhysicalLocator()).isEqualTo("客户订单明细表");

        ArgumentCaptor<LambdaQueryWrapper<ModelSyncTaskEntity>> taskCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(taskMapper).selectOne(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getSqlSelect())
                .contains("id", "tenant_id", "project_id")
                .doesNotContain("datasource_id",
                        "datasource_type",
                        "datasource_name_snapshot",
                        "batch_no",
                        "name",
                        "source",
                        "status",
                        "total_count",
                        "success_count",
                        "failed_count",
                        "stopped_count",
                        "progress_percent",
                        "stop_requested",
                        "created_by",
                        "started_at",
                        "finished_at",
                        "duration_ms",
                        "last_error");

        ArgumentCaptor<LambdaQueryWrapper<ModelSyncTaskItemEntity>> itemCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(itemMapper).selectPage(any(Page.class), itemCaptor.capture());
        assertThat(itemCaptor.getValue().getSqlSelect())
                .contains("id", "tenant_id", "project_id", "deleted", "task_id", "seq_no",
                        "physical_locator", "model_name_snapshot", "status", "message", "duration_ms")
                .doesNotContain("created_at", "updated_at", "started_at", "finished_at");
    }

    @Test
    void taskItemDurationShouldUseTaskItemStartedAndFinishedTime() throws Exception {
        ModelSyncTaskMapper taskMapper = mock(ModelSyncTaskMapper.class);
        ModelSyncTaskItemMapper itemMapper = mock(ModelSyncTaskItemMapper.class);
        DataModelService dataModelService = mock(DataModelService.class);
        ModelSyncTaskService service = modelSyncTaskService(taskMapper, itemMapper, dataModelService);

        ModelSyncTaskEntity task = new ModelSyncTaskEntity();
        task.setId(100L);
        task.setDatasourceId(200L);
        task.setTotalCount(Integer.valueOf(1));
        task.setSuccessCount(Integer.valueOf(0));
        task.setFailedCount(Integer.valueOf(0));
        task.setStoppedCount(Integer.valueOf(0));
        when(taskMapper.selectById(task.getId())).thenReturn(task);

        ModelSyncTaskItemEntity item = new ModelSyncTaskItemEntity();
        item.setTaskId(task.getId());
        item.setPhysicalLocator("lt_reg_customer_profile");
        item.setStatus(ModelSyncTaskItemStatus.PENDING.name());

        DataModelSyncItemResult result = new DataModelSyncItemResult();
        result.setPhysicalLocator(item.getPhysicalLocator());
        result.setModelName(item.getPhysicalLocator());
        result.setSuccess(false);
        result.setMessage("Access denied for user 'root'");
        result.setFinishedAt(LocalDateTime.now().plusSeconds(5));
        result.setDurationMs(Long.valueOf(2L));
        DataModelSyncBatchResult batchResult = new DataModelSyncBatchResult();
        batchResult.addItem(result);
        when(dataModelService.syncBatchFromDatasource(eq(task.getDatasourceId()), anyList())).thenReturn(batchResult);

        Method method = ModelSyncTaskService.class
                .getDeclaredMethod("processSingleItem", ModelSyncTaskEntity.class, ModelSyncTaskItemEntity.class);
        method.setAccessible(true);
        method.invoke(service, task, item);

        assertThat(item.getStatus()).isEqualTo(ModelSyncTaskItemStatus.FAILED.name());
        assertThat(item.getDurationMs()).isGreaterThan(1000L);
        assertThat(item.getDurationMs()).isNotEqualTo(2L);
        verify(dataModelService).syncBatchFromDatasource(task.getDatasourceId(), Collections.singletonList(item.getPhysicalLocator()));
        verify(itemMapper, atLeastOnce()).updateById(item);
    }

    private static void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityClass);
        }
    }

    private ModelSyncTaskService modelSyncTaskService(ModelSyncTaskMapper taskMapper,
                                                      ModelSyncTaskItemMapper itemMapper,
                                                      DataModelService dataModelService) {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(securityService.currentUserId()).thenReturn(1L);
        when(accessService.requireCurrentProjectId()).thenReturn(100L);
        when(accessService.currentProjectId()).thenReturn(100L);
        return new ModelSyncTaskService(
                taskMapper,
                itemMapper,
                mock(DataSourceService.class),
                dataModelService,
                securityService,
                accessService,
                mock(FollowSubscriptionService.class),
                mock(NotificationService.class),
                Runnable::run);
    }

    private ModelSyncTaskEntity task() {
        ModelSyncTaskEntity entity = new ModelSyncTaskEntity();
        entity.setId(100L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setDeleted(0);
        entity.setCreatedAt(LocalDateTime.of(2026, 6, 29, 9, 0, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 6, 29, 9, 5, 0));
        entity.setDatasourceId(200L);
        entity.setDatasourceType("mysql8");
        entity.setDatasourceNameSnapshot("长期回归-客户经营数据源");
        entity.setBatchNo(3);
        entity.setName("长期回归-客户模型同步");
        entity.setStatus(ModelSyncTaskStatus.RUNNING.name());
        entity.setTotalCount(10);
        entity.setSuccessCount(4);
        entity.setFailedCount(1);
        entity.setStoppedCount(0);
        entity.setProgressPercent(40);
        entity.setStopRequested(0);
        entity.setCreatedBy(1L);
        entity.setStartedAt(LocalDateTime.of(2026, 6, 29, 9, 1, 0));
        entity.setDurationMs(60000L);
        entity.setLastError("客户订单明细表同步失败");
        return entity;
    }

    private ModelSyncTaskEntity taskSummary() {
        ModelSyncTaskEntity entity = new ModelSyncTaskEntity();
        entity.setId(100L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setDeleted(0);
        entity.setCreatedAt(LocalDateTime.of(2026, 6, 29, 9, 0, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 6, 29, 9, 5, 0));
        entity.setName("长期回归-客户模型同步");
        entity.setStatus(ModelSyncTaskStatus.RUNNING.name());
        entity.setTotalCount(10);
        entity.setSuccessCount(4);
        entity.setFailedCount(1);
        entity.setStoppedCount(0);
        entity.setProgressPercent(40);
        entity.setStopRequested(0);
        entity.setDurationMs(60000L);
        return entity;
    }

    private ModelSyncTaskItemEntity taskItem(Long taskId) {
        ModelSyncTaskItemEntity entity = new ModelSyncTaskItemEntity();
        entity.setId(101L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setDeleted(0);
        entity.setCreatedAt(LocalDateTime.of(2026, 6, 29, 9, 0, 0));
        entity.setUpdatedAt(LocalDateTime.of(2026, 6, 29, 9, 5, 0));
        entity.setTaskId(taskId);
        entity.setSeqNo(1);
        entity.setPhysicalLocator("客户订单明细表");
        entity.setModelNameSnapshot("客户订单明细模型");
        entity.setStatus(ModelSyncTaskItemStatus.SUCCESS.name());
        entity.setMessage("同步成功");
        entity.setStartedAt(LocalDateTime.of(2026, 6, 29, 9, 1, 0));
        entity.setFinishedAt(LocalDateTime.of(2026, 6, 29, 9, 2, 0));
        entity.setDurationMs(60000L);
        return entity;
    }
}
