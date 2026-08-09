package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.FileTransferFileEntryView;
import com.jdragon.studio.dto.model.UnstructuredPermissionView;
import com.jdragon.studio.infra.service.UnstructuredManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;

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
                new UnstructuredManagementController(service);

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
                new UnstructuredManagementController(service)).build();

        mockMvc.perform(get("/api/v1/unstructured-management/permissions")
                        .param("datasourceId", "100")
                        .param("path", "/uat"))
                .andExpect(status().isOk());

        verify(service).permissions(100L, "/uat");
    }

    @Test
    void shouldResolveExplicitPathAclQueryParameterNames() throws Exception {
        UnstructuredManagementService service = mock(UnstructuredManagementService.class);
        when(service.pathAcl(100L, "/uat")).thenReturn(Collections.emptyList());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new UnstructuredManagementController(service)).build();

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
}
