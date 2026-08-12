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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FileTransferPublicRouteContractTest {

    @Test
    void shouldExposeAllPublicFileTransferRoutesBelowApiV1() throws Exception {
        FileTransferEventService eventService = mock(FileTransferEventService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new FileTransferBrowserController(mock(UnstructuredManagementService.class)),
                new FileTransferRunController(mock(FileTransferRunService.class), eventService),
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
        when(eventService.connect()).thenReturn(new SseEmitter());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new FileTransferRunController(mock(FileTransferRunService.class), eventService))
                .build();

        mockMvc.perform(get("/api/v1/file-transfer/runs/events")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk());
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
