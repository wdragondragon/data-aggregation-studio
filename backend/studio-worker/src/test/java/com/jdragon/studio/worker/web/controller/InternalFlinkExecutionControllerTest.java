package com.jdragon.studio.worker.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.FlinkQuestionResultView;
import com.jdragon.studio.dto.model.request.FlinkSqlExecuteRequest;
import com.jdragon.studio.dto.model.request.RuntimeFlinkSqlExecuteRequest;
import com.jdragon.studio.flink.service.FlinkSqlExecutionService;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.infra.service.RuntimeInternalHeaders;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import com.jdragon.studio.worker.web.filter.InternalApiTokenFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalFlinkExecutionControllerTest {

    @AfterEach
    void clearContext() {
        StudioRequestContextHolder.clear();
    }

    @Test
    void shouldExecuteWithPropagatedTenantProjectAndUserContext() throws Exception {
        Fixture fixture = fixture();
        StudioRequestContext previous = new StudioRequestContext();
        previous.setTenantId("control-tenant");
        previous.setProjectId(999L);
        StudioRequestContextHolder.setContext(previous);
        FlinkQuestionResultView result = new FlinkQuestionResultView();
        result.setSql("SELECT * FROM m_7");
        result.setColumns(List.of("id"));
        when(fixture.executionService.execute(any())).thenAnswer(invocation -> {
            StudioRequestContext context = StudioRequestContextHolder.getContext();
            assertEquals("tenant-a", context.getTenantId());
            assertEquals(101L, context.getProjectId());
            assertEquals(9L, context.getUserId());
            assertEquals("admin", context.getUsername());
            return result;
        });

        fixture.mockMvc.perform(post("/internal/runtime/flink/sql/execute")
                        .header("X-Studio-Internal-Token", "internal-token")
                        .contentType("application/json")
                        .content(fixture.objectMapper.writeValueAsBytes(request("C50"))))
                .andExpect(status().isOk())
                .andExpect(header().string(RuntimeInternalHeaders.RUNTIME_RESPONSE_HEADER,
                        RuntimeInternalHeaders.RUNTIME_RESPONSE_AUTHENTICATED))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sql").value("SELECT * FROM m_7"));

        assertNull(StudioRequestContextHolder.getContext());
    }

    @Test
    void shouldRejectRequestForAnotherWorkerClusterIdentity() throws Exception {
        Fixture fixture = fixture();

        fixture.mockMvc.perform(post("/internal/runtime/flink/sql/execute")
                        .header("X-Studio-Internal-Token", "internal-token")
                        .contentType("application/json")
                        .content(fixture.objectMapper.writeValueAsBytes(request("C46"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        "Runtime process identity does not match the requested target cluster"));

        verify(fixture.executionService, never()).execute(any());
    }

    @Test
    void shouldRejectProjectWithoutTargetClusterAuthorization() throws Exception {
        Fixture fixture = fixture();
        when(fixture.authorizationService.isRuntimeClusterAuthorizedForProject(
                "tenant-a", 101L, 50L)).thenReturn(false);

        fixture.mockMvc.perform(post("/internal/runtime/flink/sql/execute")
                        .header("X-Studio-Internal-Token", "internal-token")
                        .contentType("application/json")
                        .content(fixture.objectMapper.writeValueAsBytes(request("C50"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(fixture.executionService, never()).execute(any());
    }

    @Test
    void shouldRejectMissingInternalTokenBeforeExecution() throws Exception {
        Fixture fixture = fixture();

        fixture.mockMvc.perform(post("/internal/runtime/flink/sql/execute")
                        .contentType("application/json")
                        .content(fixture.objectMapper.writeValueAsBytes(request("C50"))))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(RuntimeInternalHeaders.INTERNAL_ERROR_HEADER,
                        RuntimeInternalHeaders.INTERNAL_AUTHENTICATION));

        verify(fixture.executionService, never()).execute(any());
    }

    private RuntimeFlinkSqlExecuteRequest request(String targetClusterCode) {
        FlinkSqlExecuteRequest execution = new FlinkSqlExecuteRequest();
        execution.setRuntimeClusterId(50L);
        execution.setModelIds(List.of(7L));
        execution.setSql("SELECT * FROM m_7");
        RuntimeFlinkSqlExecuteRequest request = new RuntimeFlinkSqlExecuteRequest();
        request.setTargetClusterId(50L);
        request.setTargetClusterCode(targetClusterCode);
        request.setTenantId("tenant-a");
        request.setProjectId(101L);
        request.setUserId(9L);
        request.setUsername("admin");
        request.setExecution(execution);
        return request;
    }

    private Fixture fixture() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        FlinkSqlExecutionService executionService = mock(FlinkSqlExecutionService.class);
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        WorkerAuthorizationService authorizationService = mock(WorkerAuthorizationService.class);
        RuntimeClusterEntity cluster = new RuntimeClusterEntity();
        cluster.setId(50L);
        cluster.setTenantId("tenant-a");
        cluster.setCode("C50");
        cluster.setEnabled(1);
        when(clusterMapper.selectById(50L)).thenReturn(cluster);
        when(authorizationService.isRuntimeClusterAuthorizedForProject(
                "tenant-a", 101L, 50L)).thenReturn(true);
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeClusterCode("C50");
        properties.setInternalApiToken("internal-token");
        InternalFlinkExecutionController controller = new InternalFlinkExecutionController(
                executionService, clusterMapper, properties, authorizationService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new InternalApiTokenFilter(objectMapper, properties))
                .build();
        return new Fixture(mockMvc, objectMapper, executionService, authorizationService);
    }

    private static final class Fixture {
        private final MockMvc mockMvc;
        private final ObjectMapper objectMapper;
        private final FlinkSqlExecutionService executionService;
        private final WorkerAuthorizationService authorizationService;

        private Fixture(MockMvc mockMvc,
                        ObjectMapper objectMapper,
                        FlinkSqlExecutionService executionService,
                        WorkerAuthorizationService authorizationService) {
            this.mockMvc = mockMvc;
            this.objectMapper = objectMapper;
            this.executionService = executionService;
            this.authorizationService = authorizationService;
        }
    }
}
