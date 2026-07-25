package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.request.RuntimeClusterHeartbeatRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.RuntimeClusterService;
import com.jdragon.studio.infra.service.RuntimeValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RuntimeClusterControllerBindingTest {

    @Test
    void shouldBindClusterAndProjectIdentifiersWithoutCompilerParameterMetadata() throws Exception {
        RuntimeClusterService service = mock(RuntimeClusterService.class);
        when(service.instances(42L)).thenReturn(Collections.emptyList());
        when(service.projectAuthorizations(77L)).thenReturn(Collections.emptyList());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RuntimeClusterController(
                service, mock(RuntimeValidationService.class), new StudioPlatformProperties())).build();

        mockMvc.perform(get("/api/v1/runtime-clusters/42/instances"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/runtime-clusters/project-authorizations")
                        .param("projectId", "77"))
                .andExpect(status().isOk());

        verify(service).instances(42L);
        verify(service).projectAuthorizations(77L);
    }

    @Test
    void heartbeatRequiresTheConfiguredInternalToken() {
        RuntimeClusterService service = mock(RuntimeClusterService.class);
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setInternalApiToken("runtime-controller-test-token");
        RuntimeClusterController controller = new RuntimeClusterController(
                service, mock(RuntimeValidationService.class), properties);
        RuntimeClusterHeartbeatRequest request = new RuntimeClusterHeartbeatRequest();

        StudioException rejected = assertThrows(StudioException.class,
                () -> controller.heartbeat("wrong-token", request));

        assertEquals(StudioErrorCode.UNAUTHORIZED, rejected.getCode());
        verify(service, never()).heartbeat(request);

        controller.heartbeat("runtime-controller-test-token", request);
        verify(service).heartbeat(request);
    }
}
