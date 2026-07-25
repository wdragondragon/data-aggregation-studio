package com.jdragon.studio.server.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.common.Result;
import com.jdragon.studio.dto.model.FlinkQuestionPlanView;
import com.jdragon.studio.dto.model.FlinkQuestionResultView;
import com.jdragon.studio.dto.model.request.FlinkQuestionAskRequest;
import com.jdragon.studio.dto.model.request.FlinkSqlExecuteRequest;
import com.jdragon.studio.server.web.client.StudioFlinkQueryClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlinkProxyServiceTest {

    @Test
    void shouldRouteSqlExecutionDirectlyToSelectedWorker() {
        StudioFlinkQueryClient queryClient = mock(StudioFlinkQueryClient.class);
        RuntimeFlinkExecutionRouter executionRouter = mock(RuntimeFlinkExecutionRouter.class);
        FlinkProxyService service = new FlinkProxyService(queryClient, executionRouter, new ObjectMapper());
        FlinkSqlExecuteRequest request = new FlinkSqlExecuteRequest();
        request.setRuntimeClusterId(50L);
        request.setModelIds(List.of(7L));
        request.setSql("SELECT * FROM m_7");
        FlinkQuestionResultView workerResult = new FlinkQuestionResultView();
        when(executionRouter.execute(request)).thenReturn(workerResult);

        Result<FlinkQuestionResultView> result = service.executeSql(request);

        assertSame(workerResult, result.getData());
        verify(executionRouter).execute(request);
    }

    @Test
    void shouldPlanQuestionInControlPlaneAndExecuteGeneratedSqlInWorker() {
        StudioFlinkQueryClient queryClient = mock(StudioFlinkQueryClient.class);
        RuntimeFlinkExecutionRouter executionRouter = mock(RuntimeFlinkExecutionRouter.class);
        FlinkProxyService service = new FlinkProxyService(queryClient, executionRouter, new ObjectMapper());
        FlinkQuestionAskRequest request = new FlinkQuestionAskRequest();
        request.setRuntimeClusterId(46L);
        request.setQuestion("统计订单数");

        FlinkQuestionPlanView plan = new FlinkQuestionPlanView();
        plan.setRuntimeClusterId(46L);
        plan.setSql("SELECT COUNT(*) AS total FROM m_9");
        plan.setModelIds(List.of(9L));
        plan.setMaxRows(100);
        plan.setScanMaxRows(1000);
        plan.setWarnings(List.of("planning warning"));
        when(queryClient.plan(request)).thenReturn(Result.success(plan));
        FlinkQuestionResultView workerResult = new FlinkQuestionResultView();
        when(executionRouter.execute(org.mockito.ArgumentMatchers.any(FlinkSqlExecuteRequest.class)))
                .thenReturn(workerResult);

        FlinkQuestionResultView result = service.ask(request).getData();

        ArgumentCaptor<FlinkSqlExecuteRequest> execution = ArgumentCaptor.forClass(FlinkSqlExecuteRequest.class);
        verify(executionRouter).execute(execution.capture());
        assertEquals(46L, execution.getValue().getRuntimeClusterId());
        assertEquals(List.of(9L), execution.getValue().getModelIds());
        assertEquals("SELECT COUNT(*) AS total FROM m_9", execution.getValue().getSql());
        assertEquals(100, execution.getValue().getMaxRows());
        assertEquals(1000, execution.getValue().getScanMaxRows());
        assertEquals("统计订单数", result.getQuestion());
        assertEquals(List.of("planning warning"), result.getWarnings());
    }
}
