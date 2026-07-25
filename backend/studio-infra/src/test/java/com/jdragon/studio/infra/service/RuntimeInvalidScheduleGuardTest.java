package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.CollectionTaskStatus;
import com.jdragon.studio.dto.enums.CollectionTaskType;
import com.jdragon.studio.dto.model.CollectionTaskScheduleDefinition;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.entity.CollectionTaskScheduleEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.CollectionTaskMetricBindingMapper;
import com.jdragon.studio.infra.mapper.CollectionTaskScheduleMapper;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeInvalidScheduleGuardTest {

    @Test
    void shouldRejectEnablingScheduleForRuntimeInvalidResource() {
        CollectionTaskDefinitionMapper definitionMapper = mock(CollectionTaskDefinitionMapper.class);
        CollectionTaskScheduleMapper scheduleMapper = mock(CollectionTaskScheduleMapper.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        RuntimeClusterSelectionService runtimeSelection = mock(RuntimeClusterSelectionService.class);
        CollectionTaskDefinitionEntity entity = new CollectionTaskDefinitionEntity();
        entity.setId(301L);
        entity.setTenantId("tenant-a");
        entity.setProjectId(100L);
        entity.setRuntimeClusterId(46L);
        entity.setName("采集任务");
        entity.setTaskType(CollectionTaskType.SINGLE_TABLE.name());
        entity.setStatus(CollectionTaskStatus.DRAFT.name());
        entity.setSourceCount(0);
        when(definitionMapper.selectById(301L)).thenReturn(entity);
        when(scheduleMapper.selectOne(any())).thenReturn(null);
        doThrow(new StudioException("数据源已不适用于运行集群"))
                .when(runtimeSelection)
                .assertResourceValid(StudioConstants.RESOURCE_TYPE_COLLECTION_TASK, 301L);

        CollectionTaskService service = new CollectionTaskService(
                definitionMapper,
                mock(CollectionTaskMetricBindingMapper.class),
                scheduleMapper,
                mock(DispatchTaskMapper.class),
                mock(RunRecordMapper.class),
                mock(DataSourceService.class),
                mock(DataModelService.class),
                mock(CollectionTaskAssemblerService.class),
                new ObjectMapper(),
                mock(StudioSecurityService.class),
                accessService,
                mock(DataModelLineageService.class),
                mock(DatasourceTypeCapabilityService.class));
        service.setRuntimeClusterSelectionService(runtimeSelection);
        CollectionTaskScheduleDefinition schedule = new CollectionTaskScheduleDefinition();
        schedule.setEnabled(true);
        schedule.setCronExpression("0 */5 * * * ?");
        schedule.setTimezone("Asia/Shanghai");

        StudioException exception = assertThrows(StudioException.class,
                () -> service.updateSchedule(301L, schedule));

        assertEquals("数据源已不适用于运行集群", exception.getMessage());
        verify(accessService).assertWritable(100L);
        verify(scheduleMapper, never()).insert(any(CollectionTaskScheduleEntity.class));
        verify(scheduleMapper, never()).updateById(any(CollectionTaskScheduleEntity.class));
    }
}
