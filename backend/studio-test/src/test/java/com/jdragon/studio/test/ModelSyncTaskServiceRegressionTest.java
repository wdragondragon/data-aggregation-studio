package com.jdragon.studio.test;

import com.jdragon.studio.dto.enums.ModelSyncTaskItemStatus;
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
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelSyncTaskServiceRegressionTest {

    @Test
    void taskItemDurationShouldUseTaskItemStartedAndFinishedTime() throws Exception {
        ModelSyncTaskMapper taskMapper = mock(ModelSyncTaskMapper.class);
        ModelSyncTaskItemMapper itemMapper = mock(ModelSyncTaskItemMapper.class);
        DataModelService dataModelService = mock(DataModelService.class);
        ModelSyncTaskService service = new ModelSyncTaskService(
                taskMapper,
                itemMapper,
                mock(DataSourceService.class),
                dataModelService,
                mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class),
                mock(FollowSubscriptionService.class),
                mock(NotificationService.class),
                Runnable::run);

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
}
