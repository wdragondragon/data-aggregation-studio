package com.jdragon.studio.worker.web.controller;

import com.jdragon.studio.dto.model.request.RuntimeAssistantScriptExecuteRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import com.jdragon.studio.infra.service.AssistantScriptSkillExecutionService;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalAssistantScriptControllerTest {

    @AfterEach
    void clearContext() {
        StudioRequestContextHolder.clear();
    }

    @Test
    void shouldExecuteOnMatchingAuthorizedWorkerAndRestoreContext() {
        Fixture fixture = fixture();
        StudioRequestContext previous = new StudioRequestContext();
        previous.setTenantId("previous");
        StudioRequestContextHolder.setContext(previous);
        when(fixture.executionService.execute(any())).thenAnswer(invocation -> {
            StudioRequestContext context = StudioRequestContextHolder.getContext();
            assertThat(context.getTenantId()).isEqualTo("tenant-a");
            assertThat(context.getProjectId()).isEqualTo(101L);
            assertThat(context.getUserId()).isEqualTo(9L);
            Map<String, Object> params = invocation.getArgument(0);
            assertThat(params.get("entrypointId")).isEqualTo("field-mapping-suggester");
            return Map.of("schema", "studio.script-result.v1", "success", true);
        });

        var response = fixture.controller.execute(request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(StudioRequestContextHolder.getContext()).isSameAs(previous);
    }

    @Test
    void shouldRejectWorkerClusterIdentityMismatchBeforeExecution() {
        Fixture fixture = fixture();
        RuntimeAssistantScriptExecuteRequest request = request();
        request.setTargetClusterCode("C46");

        var response = fixture.controller.execute(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(fixture.executionService, never()).execute(any());
    }

    @Test
    void shouldRejectProjectWithoutClusterAuthorization() {
        Fixture fixture = fixture();
        when(fixture.authorizationService.isRuntimeClusterAuthorizedForProject(
                "tenant-a", 101L, 50L)).thenReturn(false);

        var response = fixture.controller.execute(request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(fixture.executionService, never()).execute(any());
    }

    @Test
    void shouldClearContextAfterExecutionFailure() {
        Fixture fixture = fixture();
        when(fixture.executionService.execute(any())).thenThrow(new IllegalStateException("python unavailable"));

        var response = fixture.controller.execute(request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(StudioRequestContextHolder.getContext()).isNull();
    }

    private Fixture fixture() {
        AssistantScriptSkillExecutionService executionService = mock(AssistantScriptSkillExecutionService.class);
        RuntimeClusterMapper clusterMapper = mock(RuntimeClusterMapper.class);
        WorkerAuthorizationService authorizationService = mock(WorkerAuthorizationService.class);
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setRuntimeClusterCode("C50");
        RuntimeClusterEntity cluster = new RuntimeClusterEntity();
        cluster.setId(50L);
        cluster.setTenantId("tenant-a");
        cluster.setCode("C50");
        cluster.setEnabled(1);
        when(clusterMapper.selectById(50L)).thenReturn(cluster);
        when(authorizationService.isRuntimeClusterAuthorizedForProject(
                "tenant-a", 101L, 50L)).thenReturn(true);
        InternalAssistantScriptController controller = new InternalAssistantScriptController(
                executionService, clusterMapper, properties, authorizationService);
        return new Fixture(controller, executionService, authorizationService);
    }

    private RuntimeAssistantScriptExecuteRequest request() {
        RuntimeAssistantScriptExecuteRequest request = new RuntimeAssistantScriptExecuteRequest();
        request.setTargetClusterId(50L);
        request.setTargetClusterCode("C50");
        request.setTenantId("tenant-a");
        request.setProjectId(101L);
        request.setUserId(9L);
        request.setUsername("admin");
        request.setEntrypointId("field-mapping-suggester");
        request.setInput(Map.of(
                "sourceFields", List.of("id"),
                "targetFields", List.of("ID")));
        return request;
    }

    private static final class Fixture {
        private final InternalAssistantScriptController controller;
        private final AssistantScriptSkillExecutionService executionService;
        private final WorkerAuthorizationService authorizationService;

        private Fixture(InternalAssistantScriptController controller,
                        AssistantScriptSkillExecutionService executionService,
                        WorkerAuthorizationService authorizationService) {
            this.controller = controller;
            this.executionService = executionService;
            this.authorizationService = authorizationService;
        }
    }
}
