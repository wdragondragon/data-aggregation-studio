package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.FileTransferFileEntryView;
import com.jdragon.studio.dto.model.UnstructuredPermissionView;
import com.jdragon.studio.dto.model.UnstructuredDownloadTicketView;
import com.jdragon.studio.dto.model.UnstructuredUploadResultView;
import com.jdragon.studio.dto.model.request.UnstructuredDownloadTicketRequest;
import com.jdragon.studio.infra.service.UnstructuredDownloadTicketService;
import com.jdragon.studio.infra.service.UnstructuredManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UnstructuredManagementControllerContractTest {

    @Test
    void shouldStreamUsingPreAuthorizedDownloadContext() throws Exception {
        UnstructuredManagementService service = mock(UnstructuredManagementService.class);
        FileTransferFileEntryView entry = new FileTransferFileEntryView();
        entry.setName("smoke.txt");
        entry.setSize(5L);
        UnstructuredManagementService.PreparedDownload prepared =
                new UnstructuredManagementService.PreparedDownload(
                        new DataSourceDefinition(), 50L, "/smoke.txt", entry);
        when(service.prepareDownload(50L, 100L, "/smoke.txt")).thenReturn(prepared);
        UnstructuredManagementController controller =
                controller(service);

        var response = controller.download(50L, 100L, "/smoke.txt");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertThat(response.getHeaders().getContentLength()).isEqualTo(5L);
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .contains("smoke.txt");
        verify(service).prepareDownload(50L, 100L, "/smoke.txt");
        verify(service).download(prepared, output);
    }

    @Test
    void shouldResolveExplicitPermissionQueryParameterNames() throws Exception {
        UnstructuredManagementService service = mock(UnstructuredManagementService.class);
        when(service.permissions(100L, "/uat")).thenReturn(new UnstructuredPermissionView());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                controller(service)).build();

        mockMvc.perform(get("/api/v1/unstructured-management/permissions")
                        .param("datasourceId", "100")
                        .param("path", "/uat"))
                .andExpect(status().isOk());

        verify(service).permissions(100L, "/uat");
    }

    @Test
    void shouldRequireContentLengthForStreamingUpload() throws Exception {
        UnstructuredManagementService service = mock(UnstructuredManagementService.class);
        UnstructuredManagementController controller = controller(service);
        MockHttpServletRequest request = new MockHttpServletRequest();

        var response = controller.upload(50L, 100L, "/upload.txt", false, request);

        assertThat(response.getStatusCode().value()).isEqualTo(411);
    }

    @Test
    void shouldStreamUploadThroughService() throws Exception {
        UnstructuredManagementService service = mock(UnstructuredManagementService.class);
        UnstructuredUploadResultView result = new UnstructuredUploadResultView();
        result.setBytes(5L);
        when(service.upload(org.mockito.ArgumentMatchers.eq(50L),
                org.mockito.ArgumentMatchers.eq(100L),
                org.mockito.ArgumentMatchers.eq("/upload.txt"),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.any())).thenReturn(result);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        var response = controller(service)
                .upload(50L, 100L, "/upload.txt", true, request);

        assertThat(response.getBody().getData().getBytes()).isEqualTo(5L);
        verify(service).upload(org.mockito.ArgumentMatchers.eq(50L),
                org.mockito.ArgumentMatchers.eq(100L),
                org.mockito.ArgumentMatchers.eq("/upload.txt"),
                org.mockito.ArgumentMatchers.eq(true),
                org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldStreamDirectoryArchiveWithoutContentLength() throws Exception {
        UnstructuredManagementService service = mock(UnstructuredManagementService.class);
        UnstructuredManagementService.PreparedArchive prepared =
                new UnstructuredManagementService.PreparedArchive(
                        new DataSourceDefinition(), 50L, List.of("/reports"),
                        "reports.zip", 1L, "admin");
        when(service.prepareArchive(50L, 100L, List.of("/reports"))).thenReturn(prepared);
        com.jdragon.studio.dto.model.request.UnstructuredArchiveDownloadRequest request =
                new com.jdragon.studio.dto.model.request.UnstructuredArchiveDownloadRequest();
        request.setRuntimeClusterId(50L);
        request.setDatasourceId(100L);
        request.setPaths(List.of("/reports"));

        var response = controller(service).downloadArchive(request);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/zip");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(-1L);
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("reports.zip");
        verify(service).downloadArchive(prepared, output);
    }

    @Test
    void shouldResolveExplicitStatQueryParameterNames() throws Exception {
        UnstructuredManagementService service = mock(UnstructuredManagementService.class);
        when(service.statForUpload(50L, 100L, "/upload.txt"))
                .thenReturn(new FileTransferFileEntryView());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                controller(service)).build();

        mockMvc.perform(get("/api/v1/unstructured-management/stat")
                        .param("runtimeClusterId", "50")
                        .param("datasourceId", "100")
                        .param("path", "/upload.txt"))
                .andExpect(status().isOk());

        verify(service).statForUpload(50L, 100L, "/upload.txt");
    }

    @Test
    void shouldResolveExplicitPathAclQueryParameterNames() throws Exception {
        UnstructuredManagementService service = mock(UnstructuredManagementService.class);
        when(service.pathAcl(100L, "/uat")).thenReturn(Collections.emptyList());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                controller(service)).build();

        mockMvc.perform(get("/api/v1/unstructured-management/acl/path")
                        .param("datasourceId", "100")
                        .param("path", "/uat"))
                .andExpect(status().isOk());

        verify(service).pathAcl(100L, "/uat");
    }

    @Test
    void shouldDeclareExplicitNamesForEveryPathVariable() {
        Arrays.stream(UnstructuredManagementController.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameters()))
                .map(parameter -> parameter.getAnnotation(PathVariable.class))
                .filter(annotation -> annotation != null)
                .forEach(annotation -> assertThat(annotation.value()).isNotBlank());
    }

    @Test
    void shouldCreateDownloadTicketUsingUnifiedResult() {
        UnstructuredManagementService service = mock(UnstructuredManagementService.class);
        UnstructuredDownloadTicketService ticketService = mock(UnstructuredDownloadTicketService.class);
        UnstructuredDownloadTicketRequest request = new UnstructuredDownloadTicketRequest();
        request.setRuntimeClusterId(50L);
        request.setDatasourceId(100L);
        request.setPaths(List.of("/smoke.txt"));
        UnstructuredDownloadTicketView ticket = new UnstructuredDownloadTicketView();
        ticket.setTicket("ticket");
        when(ticketService.create(request)).thenReturn(ticket);

        var result = new UnstructuredManagementController(service, ticketService)
                .createDownloadTicket(request);

        assertThat(result.getData()).isSameAs(ticket);
        verify(ticketService).create(request);
    }

    @Test
    void shouldStreamNativeFileWithBrowserDownloadHeaders() throws Exception {
        UnstructuredManagementService service = mock(UnstructuredManagementService.class);
        UnstructuredDownloadTicketService ticketService = mock(UnstructuredDownloadTicketService.class);
        FileTransferFileEntryView entry = new FileTransferFileEntryView();
        entry.setName("native.txt");
        entry.setSize(6L);
        UnstructuredManagementService.PreparedDownload download =
                new UnstructuredManagementService.PreparedDownload(
                        new DataSourceDefinition(), 50L, "/native.txt", entry);
        UnstructuredManagementService.PreparedNativeDownload prepared =
                new UnstructuredManagementService.PreparedNativeDownload(
                        false, download, null, "native.txt", 6L, List.of("/native.txt"));
        when(ticketService.consume("valid-ticket")).thenReturn(prepared);

        var response = new UnstructuredManagementController(service, ticketService)
                .downloadNative("valid-ticket");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("application/octet-stream");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(6L);
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("native.txt");
        assertThat(response.getHeaders().getFirst("Cache-Control")).contains("no-store");
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
        verify(service).download(download, output);
    }

    @Test
    void shouldStreamNativeArchiveWithoutContentLength() throws Exception {
        UnstructuredManagementService service = mock(UnstructuredManagementService.class);
        UnstructuredDownloadTicketService ticketService = mock(UnstructuredDownloadTicketService.class);
        UnstructuredManagementService.PreparedArchive archive =
                new UnstructuredManagementService.PreparedArchive(
                        new DataSourceDefinition(), 50L, List.of("/reports"),
                        "reports.zip", 1L, "admin");
        UnstructuredManagementService.PreparedNativeDownload prepared =
                new UnstructuredManagementService.PreparedNativeDownload(
                        true, null, archive, "reports.zip", null, List.of("/reports"));
        when(ticketService.consume("valid-ticket")).thenReturn(prepared);

        var response = new UnstructuredManagementController(service, ticketService)
                .downloadNative("valid-ticket");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);

        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/zip");
        assertThat(response.getHeaders().getContentLength()).isEqualTo(-1L);
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("reports.zip");
        verify(service).downloadArchive(archive, output);
    }

    private UnstructuredManagementController controller(UnstructuredManagementService service) {
        return new UnstructuredManagementController(service,
                mock(UnstructuredDownloadTicketService.class));
    }
}
