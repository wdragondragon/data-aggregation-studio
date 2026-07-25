package com.jdragon.studio.server.web.controller;

import com.jdragon.studio.dto.model.request.CollectionTaskSaveRequest;
import com.jdragon.studio.dto.model.request.DataDevelopmentScriptSaveRequest;
import com.jdragon.studio.dto.model.request.DataIngestionServiceSaveRequest;
import com.jdragon.studio.dto.model.request.DataServiceSaveRequest;
import com.jdragon.studio.dto.model.request.DataSourceSaveRequest;
import com.jdragon.studio.dto.model.request.ProtocolConversionServiceSaveRequest;
import com.jdragon.studio.dto.model.request.QualityTaskSaveRequest;
import com.jdragon.studio.dto.model.request.WorkflowSaveRequest;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeClusterRequestContractTest {

    @Test
    void executableResourceSaveRequestsShouldRequireRuntimeCluster() throws Exception {
        List<Class<?>> requestTypes = Arrays.asList(
                CollectionTaskSaveRequest.class,
                QualityTaskSaveRequest.class,
                WorkflowSaveRequest.class,
                DataDevelopmentScriptSaveRequest.class,
                DataServiceSaveRequest.class,
                DataIngestionServiceSaveRequest.class,
                ProtocolConversionServiceSaveRequest.class);

        for (Class<?> requestType : requestTypes) {
            assertNotNull(requestType.getDeclaredField("runtimeClusterId").getAnnotation(NotNull.class),
                    requestType.getSimpleName() + ".runtimeClusterId must be required");
        }
        assertNotNull(DataSourceSaveRequest.class.getDeclaredField("applicableClusterIds")
                        .getAnnotation(NotEmpty.class),
                "DataSourceSaveRequest.applicableClusterIds must not be empty");
    }

    @Test
    void datasourceAndModelExecutionEndpointsShouldRequireRuntimeClusterQueryParameter() {
        assertRuntimeClusterRequestParamRequired(DataSourceController.class,
                "options", "test", "testCurrent", "connectionHistory", "discover", "discoverOptions");
        assertRuntimeClusterRequestParamRequired(DataDevelopmentController.class,
                "datasources", "datasourceOptions");
        assertRuntimeClusterRequestParamRequired(ModelController.class,
                "listSelectorOptions", "sync", "preview");
    }

    @Test
    void workflowResourceOptionsShouldRequireRuntimeClusterQueryParameter() {
        assertRuntimeClusterRequestParamRequired(CollectionTaskController.class, "listWorkflowOptions");
        assertRuntimeClusterRequestParamRequired(QualityTaskController.class, "listWorkflowOptions");
    }

    private void assertRuntimeClusterRequestParamRequired(Class<?> controllerType, String... methodNames) {
        for (String methodName : methodNames) {
            Method method = Arrays.stream(controllerType.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(controllerType.getSimpleName() + "." + methodName));
            Parameter parameter = Arrays.stream(method.getParameters())
                    .filter(candidate -> {
                        RequestParam annotation = candidate.getAnnotation(RequestParam.class);
                        return annotation != null && ("runtimeClusterId".equals(annotation.name())
                                || "runtimeClusterId".equals(annotation.value()));
                    })
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(methodName + " runtimeClusterId parameter missing"));
            assertTrue(parameter.getAnnotation(RequestParam.class).required(),
                    controllerType.getSimpleName() + "." + methodName + " runtimeClusterId must be required");
        }
    }
}
