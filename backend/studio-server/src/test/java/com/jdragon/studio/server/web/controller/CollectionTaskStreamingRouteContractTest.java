package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.model.CollectionTaskListView;
import com.jdragon.studio.dto.model.CollectionTaskStreamingRuntimeView;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.DispatchService;
import com.jdragon.studio.infra.service.RunTerminationService;
import com.jdragon.studio.server.web.service.RunLogProxyService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CollectionTaskStreamingRouteContractTest {

    @Test
    void exposesStreamingLifecycleAndRuntimeRoutesWithStudioResultEnvelope() throws Exception {
        CollectionTaskService collectionTaskService = mock(CollectionTaskService.class);
        CollectionTaskListView summary = new CollectionTaskListView();
        summary.setId(42L);
        when(collectionTaskService.offline(42L)).thenReturn(summary);
        when(collectionTaskService.recover(42L)).thenReturn(summary);
        when(collectionTaskService.streamingRuntime(42L)).thenReturn(new CollectionTaskStreamingRuntimeView());
        when(collectionTaskService.streamingMetricsPage(42L,
                LocalDateTime.parse("2026-08-27T01:00:00"),
                LocalDateTime.parse("2026-08-27T02:00:00"), 2, 25, false)).thenReturn(PageView.of(
                2, 25, 0L, Collections.emptyList()));
        when(collectionTaskService.streamingEvents(42L, 2, 25)).thenReturn(PageView.of(
                2, 25, 0L, Collections.emptyList()));
        when(collectionTaskService.streamingLogChunks(42L, 3, 10)).thenReturn(PageView.of(
                3, 10, 0L, Collections.emptyList()));
        RunLogProxyService runLogProxyService = mock(RunLogProxyService.class);
        when(runLogProxyService.viewChunk(42L, 88L, 2, 4096)).thenReturn(new RunLogView());

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CollectionTaskController(
                collectionTaskService, mock(DispatchService.class), mock(RunTerminationService.class), runLogProxyService)).build();

        mockMvc.perform(post("/api/v1/collection-tasks/42/offline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(42));
        mockMvc.perform(post("/api/v1/collection-tasks/42/recover"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(42));
        mockMvc.perform(get("/api/v1/collection-tasks/42/streaming-runtime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists());
                mockMvc.perform(get("/api/v1/collection-tasks/42/streaming-metrics")
                        .param("startTime", "2026-08-27T01:00:00")
                        .param("endTime", "2026-08-27T02:00:00")
                        .param("pageNo", "2")
                        .param("pageSize", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageNo").value(2))
                .andExpect(jsonPath("$.data.items").isArray());
        mockMvc.perform(get("/api/v1/collection-tasks/42/streaming-metrics")
                        .param("pageNo", "2")
                        .param("pageSize", "25")
                        .param("onlyWithRecords", "true"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/collection-tasks/42/streaming-events")
                        .param("pageNo", "2")
                        .param("pageSize", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageNo").value(2));
        mockMvc.perform(get("/api/v1/collection-tasks/42/streaming-log-chunks")
                        .param("pageNo", "3")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageNo").value(3));
        mockMvc.perform(get("/api/v1/collection-tasks/42/streaming-log-chunks/88/preview")
                        .param("pageNo", "2")
                        .param("pageSizeBytes", "4096"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists());

        verify(collectionTaskService).offline(42L);
        verify(collectionTaskService).recover(42L);
        verify(collectionTaskService).streamingRuntime(42L);
        verify(collectionTaskService).streamingMetricsPage(42L,
                LocalDateTime.parse("2026-08-27T01:00:00"),
                LocalDateTime.parse("2026-08-27T02:00:00"), 2, 25, false);
        verify(collectionTaskService).streamingEvents(42L, 2, 25);
        verify(collectionTaskService).streamingLogChunks(42L, 3, 10);
        verify(collectionTaskService).streamingMetricsPage(42L, null, null, 2, 25, true);
        verify(runLogProxyService).viewChunk(42L, 88L, 2, 4096);
    }
}
