package com.jdragon.studio.worker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.BusinessMetaModelMetadataService;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataDevelopmentSqlExecutor;
import com.jdragon.studio.infra.service.DataIngestionExecutionSupport;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceClusterBindingService;
import com.jdragon.studio.infra.service.DatasourceTypeCapabilityService;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.JavaDataDevelopmentExecutor;
import com.jdragon.studio.infra.service.PythonDataDevelopmentExecutor;
import com.jdragon.studio.infra.service.QualityTaskExecutionPlanService;
import com.jdragon.studio.infra.service.QualityTaskExecutionService;
import com.jdragon.studio.infra.service.RuntimeDatasourceProbeExecutor;
import com.jdragon.studio.infra.service.RuntimeEndpointHttpClient;
import com.jdragon.studio.infra.service.RuntimeEndpointSecurityService;
import com.jdragon.studio.infra.service.ScriptEnvironmentArtifactLoader;
import com.jdragon.studio.infra.service.ScriptEnvironmentService;
import com.jdragon.studio.infra.service.ScriptEnvironmentRuntimeService;
import com.jdragon.studio.infra.service.EnvironmentDependencyService;
import com.jdragon.studio.infra.service.CloudObjectStorageService;
import com.jdragon.studio.infra.service.StudioTransformerExecutionSupport;
import com.jdragon.studio.infra.service.StudioTransformerSupport;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers plugin-backed datasource execution only in the Worker application. */
@Configuration(proxyBeanMethods = false)
public class WorkerDatasourceExecutionConfiguration {

    @Bean
    public ScriptEnvironmentArtifactLoader scriptEnvironmentArtifactLoader(
            CloudObjectStorageService cloudObjectStorageService,
            RuntimeEndpointSecurityService runtimeEndpointSecurityService,
            RuntimeEndpointHttpClient runtimeEndpointHttpClient,
            StudioPlatformProperties properties) {
        return new ScriptEnvironmentArtifactLoader(
                cloudObjectStorageService,
                runtimeEndpointSecurityService,
                runtimeEndpointHttpClient,
                properties);
    }

    @Bean
    public ScriptEnvironmentRuntimeService scriptEnvironmentRuntimeService(
            ScriptEnvironmentService environmentService,
            EnvironmentDependencyService dependencyService,
            ScriptEnvironmentArtifactLoader artifactLoader) {
        return new ScriptEnvironmentRuntimeService(
                environmentService, dependencyService, artifactLoader);
    }

    @Bean
    public DataDevelopmentSqlExecutor dataDevelopmentSqlExecutor(
            EncryptionService encryptionService,
            DatasourceTypeCapabilityService datasourceTypeCapabilityService) {
        return new DataDevelopmentSqlExecutor(encryptionService, datasourceTypeCapabilityService);
    }

    @Bean
    public DataIngestionExecutionSupport dataIngestionExecutionSupport(
            CollectionTaskAssemblerService collectionTaskAssemblerService,
            ObjectMapper objectMapper) {
        return new DataIngestionExecutionSupport(collectionTaskAssemblerService, objectMapper);
    }

    @Bean
    public StudioTransformerExecutionSupport studioTransformerExecutionSupport(
            StudioTransformerSupport transformerSupport) {
        return new StudioTransformerExecutionSupport(transformerSupport);
    }

    @Bean
    public JavaDataDevelopmentExecutor javaDataDevelopmentExecutor(
            DataSourceService dataSourceService,
            DataModelService dataModelService,
            DataDevelopmentSqlExecutor sqlExecutor,
            ScriptEnvironmentRuntimeService environmentRuntimeService,
            DatasourceClusterBindingService datasourceClusterBindingService) {
        return new JavaDataDevelopmentExecutor(
                dataSourceService, dataModelService, sqlExecutor, environmentRuntimeService,
                datasourceClusterBindingService);
    }

    @Bean
    public PythonDataDevelopmentExecutor pythonDataDevelopmentExecutor(
            StudioPlatformProperties properties,
            ObjectMapper objectMapper,
            DataSourceService dataSourceService,
            DataModelService dataModelService,
            DataDevelopmentSqlExecutor sqlExecutor,
            DatasourceClusterBindingService datasourceClusterBindingService) {
        return new PythonDataDevelopmentExecutor(
                properties, objectMapper, dataSourceService, dataModelService, sqlExecutor,
                datasourceClusterBindingService);
    }

    @Bean
    public QualityTaskExecutionService qualityTaskExecutionService(
            DataSourceService dataSourceService,
            DataDevelopmentSqlExecutor sqlExecutor,
            QualityTaskExecutionPlanService executionPlanService) {
        return new QualityTaskExecutionService(dataSourceService, sqlExecutor, executionPlanService);
    }

    @Bean
    public AggregationSourceCapabilityProvider aggregationSourceCapabilityProvider(
            StudioPlatformProperties properties,
            EncryptionService encryptionService,
            BusinessMetaModelMetadataService businessMetaModelMetadataService) {
        return new AggregationSourceCapabilityProvider(
                properties, encryptionService, businessMetaModelMetadataService);
    }

    @Bean
    public RuntimeDatasourceProbeExecutor runtimeDatasourceProbeExecutor(
            AggregationSourceCapabilityProvider provider,
            DataDevelopmentSqlExecutor sqlExecutor) {
        return new RuntimeDatasourceProbeExecutor(provider, sqlExecutor);
    }
}
