package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.RunTerminationView;
import com.jdragon.studio.infra.entity.DispatchTaskEntity;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.RunService;
import com.jdragon.studio.infra.service.RunTerminationService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunTerminationServiceRegressionTest {

    @BeforeAll
    static void initializeLambdaMetadata() {
        if (TableInfoHelper.getTableInfo(DispatchTaskEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), "run-termination-dispatch-test"),
                    DispatchTaskEntity.class);
        }
        if (TableInfoHelper.getTableInfo(RunRecordEntity.class) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), "run-termination-run-test"),
                    RunRecordEntity.class);
        }
    }

    @Test
    void shouldFailQueuedCollectionTaskWithoutRunRecord() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        CollectionTaskDefinitionView definition = definition(10L, 100L);
        DispatchTaskEntity task = task(1L, "QUEUED", "COLLECTION_TASK", null);
        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(dispatchTaskMapper.selectById(1L)).thenReturn(task);
        doAnswer(invocation -> {
            applyDispatchUpdate(task, invocation.getArgument(1));
            return 1;
        }).when(dispatchTaskMapper).update(isNull(), any(LambdaUpdateWrapper.class));

        RunTerminationView result = service(dispatchTaskMapper, runRecordMapper, definition)
                .terminateCollectionTask(10L);

        assertEquals("FAILED", result.getStatus());
        assertTrue(result.isChanged());
        assertTrue(result.isTerminationRequested());
        assertEquals(RunTerminationService.ERROR_CODE, task.getPayloadJson().get("errorCode"));
        verify(runRecordMapper, org.mockito.Mockito.never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void shouldFailRunningWorkflowNodeAndItsSingleRunRecord() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        CollectionTaskDefinitionView definition = definition(10L, 100L);
        DispatchTaskEntity task = task(2L, "RUNNING", "WORKFLOW_NODE", 20L);
        RunRecordEntity record = record(20L, "RUNNING");
        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(dispatchTaskMapper.selectById(2L)).thenReturn(task);
        when(runRecordMapper.selectById(20L)).thenReturn(record);
        doAnswer(invocation -> {
            applyDispatchUpdate(task, invocation.getArgument(1));
            return 1;
        }).when(dispatchTaskMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        doAnswer(invocation -> {
            applyRunRecordUpdate(record, invocation.getArgument(1));
            return 1;
        }).when(runRecordMapper).update(isNull(), any(LambdaUpdateWrapper.class));

        RunTerminationView result = service(dispatchTaskMapper, runRecordMapper, definition)
                .terminateCollectionTask(10L);

        assertEquals("FAILED", result.getStatus());
        assertEquals(20L, result.getRunRecordId());
        assertEquals("FAILED", task.getStatus());
        assertEquals("FAILED", record.getStatus());
        assertEquals("USER_TERMINATED", record.getPayloadJson().get("errorCode"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<RunRecordEntity>> runUpdate =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(runRecordMapper).update(isNull(), runUpdate.capture());
        assertTrue(runUpdate.getValue().getSqlSet().contains("payload_json"));
        assertTrue(runUpdate.getValue().getSqlSet().contains("result_json"));
        assertTrue(runUpdate.getValue().getSqlSet().contains("termination_requested"));
        assertTrue(runUpdate.getValue().getSqlSet().contains("status"));
    }

    @Test
    void shouldRejectTaskLevelTerminationWhenSeveralInstancesAreActive() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        CollectionTaskDefinitionView definition = definition(10L, 100L);
        when(dispatchTaskMapper.selectList(any())).thenReturn(Arrays.asList(
                task(1L, "RUNNING", "COLLECTION_TASK", 11L),
                task(2L, "QUEUED", "WORKFLOW_NODE", null)));

        assertThrows(StudioException.class,
                () -> service(dispatchTaskMapper, mock(RunRecordMapper.class), definition)
                        .terminateCollectionTask(10L));
    }

    @Test
    void shouldRejectCollectionTaskFromAnotherTenant() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        CollectionTaskDefinitionView definition = definition(10L, 100L);
        definition.setTenantId("tenant-b");

        assertThrows(StudioException.class,
                () -> service(dispatchTaskMapper, mock(RunRecordMapper.class), definition)
                        .terminateCollectionTask(10L));
    }

    @Test
    void shouldBeIdempotentForAlreadyFailedRunRecord() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        CollectionTaskDefinitionView definition = definition(10L, 100L);
        DispatchTaskEntity task = task(3L, "FAILED", "COLLECTION_TASK", 30L);
        task.setTerminationRequested(1);
        RunRecordEntity record = record(30L, "FAILED");
        record.setTerminationRequested(1);
        RunService runService = mock(RunService.class);
        when(runService.getEntity(30L)).thenReturn(record);
        when(runRecordMapper.selectById(30L)).thenReturn(record);
        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(dispatchTaskMapper.selectById(3L)).thenReturn(task);

        RunTerminationView result = service(dispatchTaskMapper, runRecordMapper, definition, runService)
                .terminateRunRecord(30L);

        assertEquals("FAILED", result.getStatus());
        assertFalse(result.isChanged());
        assertTrue(result.isTerminationRequested());
    }

    @Test
    void shouldBeIdempotentForCollectionTaskAfterItsInstanceWasFailed() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        CollectionTaskDefinitionView definition = definition(10L, 100L);
        DispatchTaskEntity task = task(31L, "FAILED", "COLLECTION_TASK", 310L);
        task.setTerminationRequested(1);
        RunRecordEntity record = record(310L, "FAILED");
        record.setTerminationRequested(1);
        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.emptyList(), Collections.singletonList(task));
        when(dispatchTaskMapper.selectById(31L)).thenReturn(task);
        when(runRecordMapper.selectById(310L)).thenReturn(record);

        RunTerminationView result = service(dispatchTaskMapper, runRecordMapper, definition)
                .terminateCollectionTask(10L);

        assertEquals("FAILED", result.getStatus());
        assertEquals(31L, result.getDispatchTaskId());
        assertEquals(310L, result.getRunRecordId());
        assertFalse(result.isChanged());
        assertTrue(result.isTerminationRequested());
        assertEquals(RunTerminationService.REASON, result.getMessage());
    }

    @Test
    void shouldWinWhenWorkerCompletesDuringManualTermination() {
        DispatchTaskMapper dispatchTaskMapper = mock(DispatchTaskMapper.class);
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        CollectionTaskDefinitionView definition = definition(10L, 100L);
        DispatchTaskEntity task = task(4L, "RUNNING", "COLLECTION_TASK", 40L);
        RunRecordEntity record = record(40L, "RUNNING");
        when(dispatchTaskMapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(dispatchTaskMapper.selectById(4L)).thenReturn(task);
        when(runRecordMapper.selectById(40L)).thenReturn(record);

        AtomicInteger dispatchUpdates = new AtomicInteger();
        doAnswer(invocation -> {
            if (dispatchUpdates.getAndIncrement() == 0) {
                task.setStatus("SUCCESS");
                return 0;
            }
            applyDispatchUpdate(task, invocation.getArgument(1));
            return 1;
        }).when(dispatchTaskMapper).update(isNull(), any(LambdaUpdateWrapper.class));

        AtomicInteger runUpdates = new AtomicInteger();
        doAnswer(invocation -> {
            if (runUpdates.getAndIncrement() == 0) {
                record.setStatus("SUCCESS");
                return 0;
            }
            applyRunRecordUpdate(record, invocation.getArgument(1));
            return 1;
        }).when(runRecordMapper).update(isNull(), any(LambdaUpdateWrapper.class));

        RunTerminationView result = service(dispatchTaskMapper, runRecordMapper, definition)
                .terminateCollectionTask(10L);

        assertEquals("FAILED", result.getStatus());
        assertEquals("FAILED", task.getStatus());
        assertEquals("FAILED", record.getStatus());
        assertTrue(result.isTerminationRequested());
        verify(dispatchTaskMapper, times(2)).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(runRecordMapper, times(2)).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    private RunTerminationService service(DispatchTaskMapper dispatchTaskMapper,
                                          RunRecordMapper runRecordMapper,
                                          CollectionTaskDefinitionView definition) {
        RunService runService = mock(RunService.class);
        return service(dispatchTaskMapper, runRecordMapper, definition, runService);
    }

    private RunTerminationService service(DispatchTaskMapper dispatchTaskMapper,
                                          RunRecordMapper runRecordMapper,
                                          CollectionTaskDefinitionView definition,
                                          RunService runService) {
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        when(collectionTaskService.get(definition.getId())).thenReturn(definition);
        when(securityService.currentTenantId()).thenReturn("tenant-a");
        when(securityService.currentProjectId()).thenReturn(100L);
        when(securityService.currentUserId()).thenReturn(7L);
        when(securityService.currentUsername()).thenReturn("operator");
        return new RunTerminationService(dispatchTaskMapper, runRecordMapper, runService,
                collectionTaskService, securityService);
    }

    private CollectionTaskDefinitionView definition(Long id, Long projectId) {
        CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
        definition.setId(id);
        definition.setProjectId(projectId);
        definition.setTenantId("tenant-a");
        definition.setName("collection-" + id);
        return definition;
    }

    private DispatchTaskEntity task(Long id, String status, String executionType, Long runRecordId) {
        DispatchTaskEntity task = new DispatchTaskEntity();
        task.setId(id);
        task.setTenantId("tenant-a");
        task.setProjectId(100L);
        task.setCollectionTaskId(10L);
        task.setExecutionType(executionType);
        task.setStatus(status);
        task.setTerminationRequested(0);
        task.setRunRecordId(runRecordId);
        task.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        task.setPayloadJson(new LinkedHashMap<String, Object>());
        return task;
    }

    private RunRecordEntity record(Long id, String status) {
        RunRecordEntity record = new RunRecordEntity();
        record.setId(id);
        record.setTenantId("tenant-a");
        record.setProjectId(100L);
        record.setCollectionTaskId(10L);
        record.setStatus(status);
        record.setTerminationRequested(0);
        record.setMessage("running");
        record.setPayloadJson(new LinkedHashMap<String, Object>());
        record.setResultJson(new LinkedHashMap<String, Object>());
        return record;
    }

    @SuppressWarnings("unchecked")
    private void applyDispatchUpdate(DispatchTaskEntity target, Object wrapperObject) {
        LambdaUpdateWrapper<DispatchTaskEntity> wrapper = (LambdaUpdateWrapper<DispatchTaskEntity>) wrapperObject;
        List<Object> values = new java.util.ArrayList<Object>(wrapper.getParamNameValuePairs().values());
        target.setStatus(stringValue(values, 0));
        target.setTerminationRequested(integerValue(values, 0));
        target.setLeaseExpiresAt(localDateTimeValue(values, 0));
        target.setPayloadJson(mapValue(values, 0));
    }

    @SuppressWarnings("unchecked")
    private void applyRunRecordUpdate(RunRecordEntity target, Object wrapperObject) {
        LambdaUpdateWrapper<RunRecordEntity> wrapper = (LambdaUpdateWrapper<RunRecordEntity>) wrapperObject;
        List<Object> values = new java.util.ArrayList<Object>(wrapper.getParamNameValuePairs().values());
        target.setStatus(stringValue(values, 0));
        target.setTerminationRequested(integerValue(values, 0));
        target.setEndedAt(localDateTimeValue(values, 0));
        target.setMessage(stringValue(values, 1));
        target.setPayloadJson(mapValue(values, 0));
        target.setResultJson(mapValue(values, 1));
    }

    private String stringValue(List<Object> values, int occurrence) {
        int seen = 0;
        for (Object value : values) {
            if (value instanceof String && seen++ == occurrence) {
                return (String) value;
            }
        }
        return null;
    }

    private Integer integerValue(List<Object> values, int occurrence) {
        int seen = 0;
        for (Object value : values) {
            if (value instanceof Integer && seen++ == occurrence) {
                return (Integer) value;
            }
        }
        return null;
    }

    private LocalDateTime localDateTimeValue(List<Object> values, int occurrence) {
        int seen = 0;
        for (Object value : values) {
            if (value instanceof LocalDateTime && seen++ == occurrence) {
                return (LocalDateTime) value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(List<Object> values, int occurrence) {
        int seen = 0;
        for (Object value : values) {
            if (value instanceof Map && seen++ == occurrence) {
                return (Map<String, Object>) value;
            }
        }
        return new LinkedHashMap<String, Object>();
    }
}
