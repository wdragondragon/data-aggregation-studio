package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.infra.service.FileTransferMetricService;
import com.jdragon.studio.infra.service.FileTransferEventService;
import com.jdragon.studio.infra.service.FileTransferRunService;
import com.jdragon.studio.infra.service.FileTransferTaskService;
import com.jdragon.studio.infra.service.RuntimeDatasourceProbeRouter;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.UnstructuredManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

class FileTransferPublicRouteContractTest {

    @Test
    void shouldExposeAllPublicFileTransferRoutesBelowApiV1() throws Exception {
        FileTransferEventService eventService = mock(FileTransferEventService.class);
        FileTransferRunService runService = mock(FileTransferRunService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new FileTransferBrowserController(mock(UnstructuredManagementService.class)),
                new FileTransferRunController(runService, eventService),
                new FileTransferTaskController(
                        mock(FileTransferTaskService.class),
                        mock(FileTransferRunService.class),
                        mock(DataSourceService.class),
                        mock(RuntimeDatasourceProbeRouter.class)),
                new FileTransferMetricController(mock(FileTransferMetricService.class)))
                .build();

        mockMvc.perform(post("/api/v1/file-transfer/browser/list")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/file-transfer/runs"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/file-transfer/runs/100"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/file-transfer/runs/100/queue"))
                .andExpect(status().isOk());
        verify(runService).dismissManualRunFromQueue(100L);
        mockMvc.perform(get("/api/v1/file-transfer-tasks"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/file-transfer-metrics/dashboard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldExposeFileTransferServerSentEventStream() throws Exception {
        FileTransferEventService eventService = mock(FileTransferEventService.class);
        when(eventService.connect(isNull(String.class))).thenReturn(new SseEmitter());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new FileTransferRunController(mock(FileTransferRunService.class), eventService))
                .build();

        mockMvc.perform(get("/api/v1/file-transfer/runs/events")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(header().string("Connection", "keep-alive"));

        verify(eventService).connect(isNull(String.class));
    }

    @Test
    void shouldForwardLastEventIdHeaderToEventService() throws Exception {
        FileTransferEventService eventService = mock(FileTransferEventService.class);
        when(eventService.connect("99")).thenReturn(new SseEmitter());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new FileTransferRunController(mock(FileTransferRunService.class), eventService))
                .build();

        mockMvc.perform(get("/api/v1/file-transfer/runs/events")
                        .header("Last-Event-ID", "99")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk());

        verify(eventService).connect("99");
    }

    @Test
    void shouldNotExposeLegacyUnversionedFileTransferRoutes() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new FileTransferRunController(mock(FileTransferRunService.class), mock(FileTransferEventService.class)),
                new FileTransferTaskController(
                        mock(FileTransferTaskService.class),
                        mock(FileTransferRunService.class),
                        mock(DataSourceService.class),
                        mock(RuntimeDatasourceProbeRouter.class)),
                new FileTransferMetricController(mock(FileTransferMetricService.class)))
                .build();

        mockMvc.perform(get("/api/file-transfer/runs"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/file-transfer-tasks"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/file-transfer-metrics/dashboard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
