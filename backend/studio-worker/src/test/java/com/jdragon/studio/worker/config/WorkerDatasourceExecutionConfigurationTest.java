package com.jdragon.studio.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.AssistantBuiltInSkillRegistry;
import com.jdragon.studio.infra.service.AssistantScriptSkillExecutionService;
import com.jdragon.studio.infra.service.BusinessMetaModelMetadataService;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.CloudObjectStorageService;
import com.jdragon.studio.infra.service.DataDevelopmentSqlExecutor;
import com.jdragon.studio.infra.service.DataIngestionExecutionSupport;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceClusterBindingService;
import com.jdragon.studio.infra.service.DatasourceTypeCapabilityService;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.EnvironmentDependencyService;
import com.jdragon.studio.infra.service.JavaDataDevelopmentExecutor;
import com.jdragon.studio.infra.service.ManagedRuntimeFileResolver;
import com.jdragon.studio.infra.service.PythonDataDevelopmentExecutor;
import com.jdragon.studio.infra.service.QualityTaskExecutionPlanService;
import com.jdragon.studio.infra.service.QualityTaskExecutionService;
import com.jdragon.studio.infra.service.RuntimeDatasourceProbeExecutor;
import com.jdragon.studio.infra.service.RuntimeDatasourceProbeRouter;
import com.jdragon.studio.infra.service.RuntimeClusterSelectionService;
import com.jdragon.studio.infra.service.RuntimeEndpointHttpClient;
import com.jdragon.studio.infra.service.RuntimeEndpointSecurityService;
import com.jdragon.studio.infra.service.RuntimeValidationService;
import com.jdragon.studio.infra.service.ScriptEnvironmentArtifactLoader;
import com.jdragon.studio.infra.service.ScriptEnvironmentRuntimeService;
import com.jdragon.studio.infra.service.ScriptEnvironmentService;
import com.jdragon.studio.infra.service.StudioTransformerExecutionSupport;
import com.jdragon.studio.infra.service.StudioTransformerSupport;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceClusterBindingMapper;
import com.jdragon.studio.infra.mapper.ProjectRuntimeClusterMapper;
import com.jdragon.studio.infra.mapper.RuntimeClusterMapper;
import com.jdragon.studio.infra.service.WorkerAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkerDatasourceExecutionConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WorkerDatasourceExecutionConfiguration.class,
                    WorkerAssistantExecutionConfiguration.class)
            .withBean(StudioPlatformProperties.class, () -> {
                StudioPlatformProperties properties = new StudioPlatformProperties();
                properties.setAggregationHome("");
                return properties;
            })
            .withBean(EncryptionService.class, () -> mock(EncryptionService.class))
            .withBean(BusinessMetaModelMetadataService.class,
                    () -> mock(BusinessMetaModelMetadataService.class))
            .withBean(AssistantBuiltInSkillRegistry.class,
                    () -> mock(AssistantBuiltInSkillRegistry.class))
            .withBean(DatasourceTypeCapabilityService.class,
                    () -> mock(DatasourceTypeCapabilityService.class))
            .withBean(DataSourceService.class, () -> mock(DataSourceService.class))
            .withBean(DatasourceClusterBindingService.class,
                    () -> mock(DatasourceClusterBindingService.class))
            .withBean(CollectionTaskAssemblerService.class,
                    () -> mock(CollectionTaskAssemblerService.class))
            .withBean(ManagedRuntimeFileResolver.class,
                    () -> mock(ManagedRuntimeFileResolver.class))
            .withBean(DataModelService.class, () -> mock(DataModelService.class))
            .withBean(RuntimeDatasourceProbeRouter.class,
                    () -> mock(RuntimeDatasourceProbeRouter.class))
            .withBean(WorkerAuthorizationService.class,
                    () -> mock(WorkerAuthorizationService.class))
            .withBean(DatasourceClusterBindingMapper.class,
                    () -> mock(DatasourceClusterBindingMapper.class))
            .withBean(RuntimeClusterSelectionService.class,
                    () -> mock(RuntimeClusterSelectionService.class))
            .withBean(RuntimeValidationService.class,
                    () -> mock(RuntimeValidationService.class))
            .withBean(DataModelMapper.class, () -> mock(DataModelMapper.class))
            .withBean(ProjectRuntimeClusterMapper.class,
                    () -> mock(ProjectRuntimeClusterMapper.class))
            .withBean(RuntimeClusterMapper.class,
                    () -> mock(RuntimeClusterMapper.class))
            .withBean(ScriptEnvironmentService.class,
                    () -> mock(ScriptEnvironmentService.class))
            .withBean(EnvironmentDependencyService.class,
                    () -> mock(EnvironmentDependencyService.class))
            .withBean(CloudObjectStorageService.class,
                    () -> mock(CloudObjectStorageService.class))
            .withBean(RuntimeEndpointSecurityService.class,
                    () -> mock(RuntimeEndpointSecurityService.class))
            .withBean(RuntimeEndpointHttpClient.class,
                    () -> mock(RuntimeEndpointHttpClient.class))
            .withBean(QualityTaskExecutionPlanService.class,
                    () -> mock(QualityTaskExecutionPlanService.class))
            .withBean(StudioTransformerSupport.class,
                    () -> new StudioTransformerSupport(new ObjectMapper()))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void workerRegistersPluginBackedDatasourceExecutionBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AggregationSourceCapabilityProvider.class);
            assertThat(context).hasSingleBean(RuntimeDatasourceProbeExecutor.class);
            assertThat(context).hasSingleBean(DataDevelopmentSqlExecutor.class);
            assertThat(context).hasSingleBean(DataIngestionExecutionSupport.class);
            assertThat(context).hasSingleBean(StudioTransformerExecutionSupport.class);
            assertThat(context).hasSingleBean(ScriptEnvironmentArtifactLoader.class);
            assertThat(context).hasSingleBean(ScriptEnvironmentRuntimeService.class);
            assertThat(context).hasSingleBean(JavaDataDevelopmentExecutor.class);
            assertThat(context).hasSingleBean(PythonDataDevelopmentExecutor.class);
            assertThat(context).hasSingleBean(QualityTaskExecutionService.class);
            assertThat(context).hasSingleBean(AssistantScriptSkillExecutionService.class);
        });
    }
}
