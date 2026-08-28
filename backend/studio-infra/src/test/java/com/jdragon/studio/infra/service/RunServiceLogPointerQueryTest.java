package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.WorkflowDefinitionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunServiceLogPointerQueryTest {

    @BeforeAll
    static void initializeTableMetadata() {
        if (TableInfoHelper.getTableInfo(RunRecordEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), "run-service-log-pointer-test"),
                    RunRecordEntity.class);
        }
    }

    @Test
    void logPointerQueryIncludesCollectionTaskIdForChunkOwnershipValidation() {
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(securityService.currentTenantId()).thenReturn("tenant-a");
        RunRecordEntity pointer = new RunRecordEntity();
        pointer.setId(91L);
        pointer.setTenantId("tenant-a");
        when(runRecordMapper.selectOne(any())).thenReturn(pointer);

        RunService service = new RunService(
                mock(DispatchTaskMapper.class),
                runRecordMapper,
                mock(CollectionTaskService.class),
                mock(QualityTaskService.class),
                mock(WorkflowDefinitionMapper.class),
                securityService,
                mock(RunMetricSummaryMapper.class));

        service.getLogPointer(91L);

        ArgumentCaptor<LambdaQueryWrapper<RunRecordEntity>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(runRecordMapper).selectOne(captor.capture());
        assertThat(String.valueOf(captor.getValue().getSqlSelect()).toLowerCase(Locale.ROOT))
                .contains("collection_task_id");
    }
}
