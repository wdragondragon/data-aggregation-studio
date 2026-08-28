package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.CollectionTaskExecutionMode;
import com.jdragon.studio.dto.enums.CollectionTaskStatus;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.CollectionTaskScheduleDefinition;
import com.jdragon.studio.dto.model.CollectionTaskSourceBinding;
import com.jdragon.studio.dto.model.CollectionTaskStreamingOptions;
import com.jdragon.studio.dto.model.CollectionTaskTargetBinding;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.request.CollectionTaskSaveRequest;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.CollectionTaskMetricBindingMapper;
import com.jdragon.studio.infra.mapper.CollectionTaskScheduleMapper;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectionTaskStreamingModeServiceTest {

    @Test
    void newTaskDefaultsToBatchWithoutCreatingDeployment() {
        Fixture fixture = fixture("mysql8", "source_table");
        CollectionTaskSaveRequest request = fixture.request();

        CollectionTaskDefinitionView saved = fixture.service.save(request);

        assertEquals(CollectionTaskExecutionMode.BATCH, saved.getExecutionMode());
        assertEquals(CollectionTaskStatus.DRAFT, saved.getStatus());
        assertTrue(fixture.saved.get().getStreamingOptionsJson().isEmpty());
        verify(fixture.streamingRuntime, never()).ensureDeployment(any());
    }

    @Test
    void editingBatchTaskPreservesItsPrimaryKey() {
        Fixture fixture = fixture("mysql8", "source_table");
        CollectionTaskDefinitionEntity existing = fixture.existingBatch();
        when(fixture.definitionMapper.selectById(existing.getId())).thenReturn(existing);
        fixture.saved.set(existing);
        CollectionTaskSaveRequest request = fixture.request();
        request.setId(existing.getId());

        CollectionTaskDefinitionView saved = fixture.service.save(request);

        assertEquals(100L, saved.getId());
        assertEquals(100L, fixture.saved.get().getId());
        verify(fixture.definitionMapper).updateById(existing);
        verify(fixture.definitionMapper, never()).insert(any(CollectionTaskDefinitionEntity.class));
    }

    @Test
    void streamingSaveUsesKafkaModelTopicAndStableDefaultGroupId() {
        Fixture fixture = fixture("kafka", "NativeStreaming-M3-topic");
        CollectionTaskSaveRequest request = fixture.request();
        request.setExecutionMode(CollectionTaskExecutionMode.STREAMING);

        CollectionTaskDefinitionView saved = fixture.service.save(request);

        assertEquals(CollectionTaskExecutionMode.STREAMING, saved.getExecutionMode());
        assertEquals("NativeStreaming-M3-topic", saved.getSourceBindings().get(0).getModelPhysicalLocator());
        assertNotNull(saved.getId());
        assertEquals("studio.default." + saved.getId(), saved.getStreamingOptions().getGroupId());
        assertEquals("studio.default." + saved.getId(),
                fixture.saved.get().getStreamingOptionsJson().get("groupId"));
        verify(fixture.streamingRuntime).ensureDeployment(fixture.saved.get());
    }

    @Test
    void streamingSaveRejectsNonKafkaBlankTopicMultipleSourcesAndSchedule() {
        Fixture nonKafka = fixture("mysql8", "source_table");
        CollectionTaskSaveRequest nonKafkaRequest = nonKafka.request();
        nonKafkaRequest.setExecutionMode(CollectionTaskExecutionMode.STREAMING);
        assertMessage(() -> nonKafka.service.save(nonKafkaRequest), "only a Kafka source");

        Fixture blankTopic = fixture("kafka", " ");
        CollectionTaskSaveRequest blankTopicRequest = blankTopic.request();
        blankTopicRequest.setExecutionMode(CollectionTaskExecutionMode.STREAMING);
        assertMessage(() -> blankTopic.service.save(blankTopicRequest), "physicalLocator");

        Fixture multiple = fixture("kafka", "NativeStreaming-M3-topic");
        CollectionTaskSaveRequest multipleRequest = multiple.request();
        multipleRequest.setExecutionMode(CollectionTaskExecutionMode.STREAMING);
        multipleRequest.getSourceBindings().add(sourceBinding(1L, 11L, "source2"));
        assertMessage(() -> multiple.service.save(multipleRequest), "exactly one source");

        Fixture scheduled = fixture("kafka", "NativeStreaming-M3-topic");
        CollectionTaskSaveRequest scheduledRequest = scheduled.request();
        scheduledRequest.setExecutionMode(CollectionTaskExecutionMode.STREAMING);
        scheduledRequest.setSchedule(new CollectionTaskScheduleDefinition());
        assertMessage(() -> scheduled.service.save(scheduledRequest), "do not support schedules");
    }

    @Test
    void streamingOptionsAreValidatedBeforePersistence() {
        Fixture fixture = fixture("kafka", "NativeStreaming-M3-topic");
        CollectionTaskSaveRequest request = fixture.request();
        request.setExecutionMode(CollectionTaskExecutionMode.STREAMING);
        CollectionTaskStreamingOptions options = new CollectionTaskStreamingOptions();
        options.setPollTimeoutMs(0);
        request.setStreamingOptions(options);

        assertMessage(() -> fixture.service.save(request), "pollTimeoutMs must be greater than 0");
        verify(fixture.definitionMapper, never()).insert(any(CollectionTaskDefinitionEntity.class));
    }

    @Test
    void offlineStreamingTaskKeepsOfflineStatusWhenEdited() {
        Fixture fixture = fixture("kafka", "NativeStreaming-M3-topic");
        CollectionTaskDefinitionEntity existing = fixture.existingStreaming(CollectionTaskStatus.OFFLINE);
        when(fixture.definitionMapper.selectById(existing.getId())).thenReturn(existing);
        fixture.saved.set(existing);
        CollectionTaskSaveRequest request = fixture.request();
        request.setId(existing.getId());
        request.setExecutionMode(null);

        CollectionTaskDefinitionView saved = fixture.service.save(request);

        assertEquals(CollectionTaskStatus.OFFLINE, saved.getStatus());
        assertEquals(CollectionTaskExecutionMode.STREAMING, saved.getExecutionMode());
    }

    @Test
    void runningStreamingTaskRejectsEditDeleteAndSchedule() {
        Fixture fixture = fixture("kafka", "NativeStreaming-M3-topic");
        CollectionTaskDefinitionEntity existing = fixture.existingStreaming(CollectionTaskStatus.ONLINE);
        when(fixture.definitionMapper.selectById(existing.getId())).thenReturn(existing);
        when(fixture.streamingRuntime.isRunning(existing.getId())).thenReturn(true);
        doThrow(new StudioException(StudioErrorCode.BAD_REQUEST,
                "Running streaming collection task must be offline before it can be edited"))
                .when(fixture.streamingRuntime).assertEditable(existing);

        CollectionTaskSaveRequest request = fixture.request();
        request.setId(existing.getId());
        assertMessage(() -> fixture.service.save(request), "must be offline before it can be edited");
        assertMessage(() -> fixture.service.delete(existing.getId()), "must be offline before it can be deleted");
        assertMessage(() -> fixture.service.updateSchedule(existing.getId(), null), "do not support schedules");
    }

    private Fixture fixture(String sourceType, String sourceLocator) {
        CollectionTaskDefinitionMapper definitionMapper = mock(CollectionTaskDefinitionMapper.class);
        CollectionTaskMetricBindingMapper metricMapper = mock(CollectionTaskMetricBindingMapper.class);
        CollectionTaskScheduleMapper scheduleMapper = mock(CollectionTaskScheduleMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService dataModelService = mock(DataModelService.class);
        CollectionTaskAssemblerService assembler = mock(CollectionTaskAssemblerService.class);
        StudioSecurityService security = mock(StudioSecurityService.class);
        ProjectResourceAccessService access = mock(ProjectResourceAccessService.class);
        RuntimeClusterSelectionService runtimeSelection = mock(RuntimeClusterSelectionService.class);
        StreamingTaskRuntimeService streamingRuntime = mock(StreamingTaskRuntimeService.class);
        AtomicReference<CollectionTaskDefinitionEntity> saved = new AtomicReference<CollectionTaskDefinitionEntity>();

        DataSourceDefinition sourceDatasource = datasource(1L, "source", sourceType);
        DataSourceDefinition targetDatasource = datasource(2L, "target", "mysql8");
        DataModelDefinition sourceModel = model(11L, 1L, "source-model", sourceLocator);
        DataModelDefinition targetModel = model(22L, 2L, "target-model", "target_table");
        when(dataSourceService.get(1L)).thenReturn(sourceDatasource);
        when(dataSourceService.get(2L)).thenReturn(targetDatasource);
        when(dataModelService.get(11L)).thenReturn(sourceModel);
        when(dataModelService.get(22L)).thenReturn(targetModel);
        when(access.requireCurrentProjectId()).thenReturn(20L);
        when(security.currentTenantId()).thenReturn("default");
        when(security.currentUserId()).thenReturn(7L);
        when(runtimeSelection.validateDatasourceSelectionForResourceSave(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), any())).thenReturn(30L);
        when(runtimeSelection.runtimeClusterName(any(), any())).thenReturn("DEFAULT-LOCAL");
        when(runtimeSelection.hydrateRuntimeValidation(any(), any(CollectionTaskDefinitionView.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(definitionMapper.selectList(any())).thenReturn(new ArrayList<CollectionTaskDefinitionEntity>());
        when(scheduleMapper.selectOne(any())).thenReturn(null);
        when(assembler.maskHttpPhysicalLocator(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
        when(assembler.prepareReaderOptionOverrides(any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return 1;
        }).when(definitionMapper).insert(any(CollectionTaskDefinitionEntity.class));
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return 1;
        }).when(definitionMapper).updateById(any(CollectionTaskDefinitionEntity.class));
        when(definitionMapper.selectById(anyLong())).thenAnswer(invocation -> saved.get());

        CollectionTaskService service = new CollectionTaskService(
                definitionMapper, metricMapper, scheduleMapper, mock(DispatchTaskMapper.class),
                mock(RunRecordMapper.class), dataSourceService, dataModelService, assembler,
                new ObjectMapper(), security, access, mock(DataModelLineageService.class),
                mock(DatasourceTypeCapabilityService.class));
        service.setRuntimeClusterSelectionService(runtimeSelection);
        service.setStreamingTaskRuntimeService(streamingRuntime);
        return new Fixture(service, definitionMapper, streamingRuntime, saved);
    }

    private static DataSourceDefinition datasource(Long id, String name, String typeCode) {
        DataSourceDefinition definition = new DataSourceDefinition();
        definition.setId(id);
        definition.setName(name);
        definition.setTypeCode(typeCode);
        return definition;
    }

    private static DataModelDefinition model(Long id, Long datasourceId, String name, String locator) {
        DataModelDefinition definition = new DataModelDefinition();
        definition.setId(id);
        definition.setDatasourceId(datasourceId);
        definition.setName(name);
        definition.setPhysicalLocator(locator);
        return definition;
    }

    private static CollectionTaskSourceBinding sourceBinding(Long datasourceId, Long modelId, String alias) {
        CollectionTaskSourceBinding source = new CollectionTaskSourceBinding();
        source.setDatasourceId(datasourceId);
        source.setModelId(modelId);
        source.setSourceAlias(alias);
        return source;
    }

    private static void assertMessage(org.junit.jupiter.api.function.Executable executable, String fragment) {
        StudioException exception = assertThrows(StudioException.class, executable);
        assertTrue(exception.getMessage().contains(fragment), exception.getMessage());
    }

    private static final class Fixture {
        private final CollectionTaskService service;
        private final CollectionTaskDefinitionMapper definitionMapper;
        private final StreamingTaskRuntimeService streamingRuntime;
        private final AtomicReference<CollectionTaskDefinitionEntity> saved;

        private Fixture(CollectionTaskService service,
                        CollectionTaskDefinitionMapper definitionMapper,
                        StreamingTaskRuntimeService streamingRuntime,
                        AtomicReference<CollectionTaskDefinitionEntity> saved) {
            this.service = service;
            this.definitionMapper = definitionMapper;
            this.streamingRuntime = streamingRuntime;
            this.saved = saved;
        }

        private CollectionTaskSaveRequest request() {
            CollectionTaskSaveRequest request = new CollectionTaskSaveRequest();
            request.setRuntimeClusterId(30L);
            request.setName("NativeStreaming-M3-task");
            request.setSourceBindings(new ArrayList<CollectionTaskSourceBinding>(
                    List.of(sourceBinding(1L, 11L, "source"))));
            CollectionTaskTargetBinding target = new CollectionTaskTargetBinding();
            target.setDatasourceId(2L);
            target.setModelId(22L);
            request.setTargetBinding(target);
            return request;
        }

        private CollectionTaskDefinitionEntity existingStreaming(CollectionTaskStatus status) {
            CollectionTaskDefinitionEntity entity = new CollectionTaskDefinitionEntity();
            entity.setId(100L);
            entity.setTenantId("default");
            entity.setProjectId(20L);
            entity.setRuntimeClusterId(30L);
            entity.setName("NativeStreaming-M3-task");
            entity.setTaskType("SINGLE_TABLE");
            entity.setStatus(status.name());
            entity.setExecutionMode(CollectionTaskExecutionMode.STREAMING.name());
            entity.setSourceCount(1);
            entity.setStreamingOptionsJson(new ObjectMapper().convertValue(
                    new CollectionTaskStreamingOptions(), java.util.Map.class));
            return entity;
        }

        private CollectionTaskDefinitionEntity existingBatch() {
            CollectionTaskDefinitionEntity entity = existingStreaming(CollectionTaskStatus.DRAFT);
            entity.setExecutionMode(CollectionTaskExecutionMode.BATCH.name());
            entity.setStreamingOptionsJson(new java.util.LinkedHashMap<String, Object>());
            return entity;
        }
    }
}
