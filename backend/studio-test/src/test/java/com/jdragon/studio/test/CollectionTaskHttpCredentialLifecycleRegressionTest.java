package com.jdragon.studio.test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.enums.CollectionTaskStatus;
import com.jdragon.studio.dto.enums.CollectionTaskType;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.CollectionTaskSourceBinding;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.CollectionTaskDefinitionEntity;
import com.jdragon.studio.infra.mapper.CollectionTaskDefinitionMapper;
import com.jdragon.studio.infra.mapper.CollectionTaskMetricBindingMapper;
import com.jdragon.studio.infra.mapper.CollectionTaskScheduleMapper;
import com.jdragon.studio.infra.mapper.DispatchTaskMapper;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.service.CollectionTaskAssemblerService;
import com.jdragon.studio.infra.service.CollectionTaskService;
import com.jdragon.studio.infra.service.DataModelLineageService;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceTypeCapabilityService;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.HttpReaderOptionSecurityService;
import com.jdragon.studio.infra.service.PluginRuntimeOptionSchemaService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollectionTaskHttpCredentialLifecycleRegressionTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void taskDetailShouldBeMaskedWhileExecutionViewKeepsStoredCiphertext() {
        Fixture fixture = fixture();
        CollectionTaskDefinitionEntity entity = entityWithStoredBinding(fixture.storedTaskOptions);

        CollectionTaskDefinitionView apiView = ReflectionTestUtils.invokeMethod(
                fixture.collectionTaskService, "toView", entity, Boolean.TRUE);
        CollectionTaskDefinitionView executionView = ReflectionTestUtils.invokeMethod(
                fixture.collectionTaskService, "toView", entity, Boolean.FALSE);

        String apiOptions = String.valueOf(apiView.getSourceBindings().get(0).getReaderOptions());
        String executionOptions = String.valueOf(executionView.getSourceBindings().get(0).getReaderOptions());
        assertThat(apiOptions)
                .contains("****")
                .doesNotContain("task-secret")
                .doesNotContain("ENC(");
        assertThat(executionOptions)
                .contains("ENC(")
                .doesNotContain("task-secret")
                .doesNotContain("****");
    }

    @Test
    void taskDetailShouldRemainReadableWhenReferencedHttpModelWasDeleted() {
        Fixture fixture = fixture();
        when(fixture.dataModelService.get(40L)).thenThrow(
                new StudioException(StudioErrorCode.NOT_FOUND, "Model not found: 40"));
        CollectionTaskDefinitionEntity entity = entityWithStoredBinding(fixture.storedTaskOptions);

        CollectionTaskDefinitionView apiView = ReflectionTestUtils.invokeMethod(
                fixture.collectionTaskService, "toView", entity, Boolean.TRUE);

        assertThat(apiView.getSourceBindings()).hasSize(1);
        assertThat(String.valueOf(apiView.getSourceBindings().get(0).getReaderOptions()))
                .contains("****")
                .doesNotContain("task-secret")
                .doesNotContain("ENC(")
                .doesNotContain("__STUDIO_HTTP_READER_");
    }

    @Test
    void unchangedExistingTaskShouldKeepStoredSensitiveOverrideDuringEnrichment() {
        Fixture fixture = fixture();
        CollectionTaskSourceBinding submitted = sourceBinding(new LinkedHashMap<String, Object>());
        CollectionTaskSourceBinding existing = sourceBinding(fixture.storedTaskOptions);

        @SuppressWarnings("unchecked")
        List<CollectionTaskSourceBinding> enriched = ReflectionTestUtils.invokeMethod(
                fixture.collectionTaskService, "enrichSourceBindings",
                Collections.singletonList(submitted), Collections.singletonList(existing));

        assertThat(enriched).hasSize(1);
        assertThat(enriched.get(0).getReaderOptions()).isEqualTo(fixture.storedTaskOptions);
        Map<String, Object> resolved = fixture.securityService.resolveReaderOptionOverrides(
                enriched.get(0).getReaderOptions(), fixture.httpModel.getTechnicalMetadata());
        assertThat(String.valueOf(resolved.get("header"))).contains("task-secret");
    }

    @Test
    void switchingModelWithSameAliasShouldNotReuseOldTaskCredentials() {
        Fixture fixture = fixture();
        CollectionTaskSourceBinding submitted = sourceBinding(new LinkedHashMap<String, Object>());
        submitted.setModelId(41L);
        submitted.setModelName("New HTTP model");
        submitted.setModelPhysicalLocator("/new-customers");
        CollectionTaskSourceBinding existing = sourceBinding(fixture.storedTaskOptions);

        @SuppressWarnings("unchecked")
        List<CollectionTaskSourceBinding> enriched = ReflectionTestUtils.invokeMethod(
                fixture.collectionTaskService, "enrichSourceBindings",
                Collections.singletonList(submitted), Collections.singletonList(existing));

        assertThat(enriched).hasSize(1);
        assertThat(enriched.get(0).getModelId()).isEqualTo(41L);
        assertThat(enriched.get(0).getReaderOptions()).isEmpty();
        assertThat(String.valueOf(enriched.get(0).getReaderOptions()))
                .doesNotContain("ENC(")
                .doesNotContain("task-secret");
    }

    @Test
    void switchingToAnotherSourcesModelShouldNotReuseThatSourcesTaskCredentials() {
        Fixture fixture = fixture();
        CollectionTaskSourceBinding submitted = sourceBinding(new LinkedHashMap<String, Object>());

        CollectionTaskSourceBinding firstExisting = sourceBinding(fixture.storedTaskOptions);
        firstExisting.setModelId(41L);
        firstExisting.setModelName("New HTTP model");
        firstExisting.setModelPhysicalLocator("/new-customers");

        Map<String, Object> secondTaskOptions = fixture.securityService.prepareReaderOptionOverrides(
                Collections.<String, Object>singletonMap(
                        "header", "{\"Authorization\":\"Bearer second-task-secret\"}"),
                fixture.httpModel.getTechnicalMetadata());
        CollectionTaskSourceBinding secondExisting = sourceBinding(secondTaskOptions);
        secondExisting.setSourceAlias("src2");

        @SuppressWarnings("unchecked")
        List<CollectionTaskSourceBinding> enriched = ReflectionTestUtils.invokeMethod(
                fixture.collectionTaskService, "enrichSourceBindings",
                Collections.singletonList(submitted), List.of(firstExisting, secondExisting));

        assertThat(enriched).hasSize(1);
        assertThat(enriched.get(0).getReaderOptions()).isEmpty();
        assertThat(String.valueOf(enriched.get(0).getReaderOptions()))
                .doesNotContain("ENC(")
                .doesNotContain("second-task-secret");
    }

    @Test
    void explicitReaderOverrideEqualToModelDefaultShouldSurviveLaterModelDefaultChange() {
        Fixture fixture = fixture();
        readerOptions(fixture.httpModel).put("pageSize", 100);
        CollectionTaskSourceBinding submitted = sourceBinding(Collections.<String, Object>singletonMap("pageSize", 100));

        @SuppressWarnings("unchecked")
        List<CollectionTaskSourceBinding> enriched = ReflectionTestUtils.invokeMethod(
                fixture.collectionTaskService, "enrichSourceBindings",
                Collections.singletonList(submitted), Collections.emptyList());

        assertThat(enriched).hasSize(1);
        assertThat(enriched.get(0).getReaderOptions()).containsEntry("pageSize", 100);

        readerOptions(fixture.httpModel).put("pageSize", 200);
        Map<String, Object> resolved = fixture.securityService.resolveReaderOptionOverrides(
                enriched.get(0).getReaderOptions(), fixture.httpModel.getTechnicalMetadata());
        assertThat(resolved).containsEntry("pageSize", 100);
    }

    @Test
    void duplicateHttpModelWithChangedAliasShouldRejectAmbiguousMaskedCredentials() {
        Fixture fixture = fixture();
        Map<String, Object> secondTaskOptions = fixture.securityService.prepareReaderOptionOverrides(
                Collections.<String, Object>singletonMap(
                        "header", "{\"Authorization\":\"Bearer second-task-secret\"}"),
                fixture.httpModel.getTechnicalMetadata());
        CollectionTaskSourceBinding firstExisting = sourceBinding(fixture.storedTaskOptions);
        CollectionTaskSourceBinding secondExisting = sourceBinding(secondTaskOptions);
        secondExisting.setSourceAlias("src2");

        Map<String, Object> maskedOptions = fixture.securityService.maskReaderOptionOverridesForView(
                fixture.storedTaskOptions, fixture.httpModel.getTechnicalMetadata());
        CollectionTaskSourceBinding renamed = sourceBinding(maskedOptions);
        renamed.setSourceAlias("renamed_src1");

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                fixture.collectionTaskService, "enrichSourceBindings",
                Collections.singletonList(renamed), List.of(firstExisting, secondExisting)))
                .isInstanceOf(StudioException.class)
                .hasMessageContaining("multiple existing sources")
                .hasMessageContaining("Restore the original alias")
                .hasMessageContaining("re-enter the sensitive HTTP options");
    }

    private Fixture fixture() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setEncryptionSecret("collection-task-http-lifecycle-test");
        EncryptionService encryptionService = new EncryptionService(properties);
        HttpReaderOptionSecurityService securityService = new HttpReaderOptionSecurityService(encryptionService);

        DataModelDefinition httpModel = new DataModelDefinition();
        httpModel.setId(40L);
        httpModel.setDatasourceId(4L);
        httpModel.setName("HTTP model");
        httpModel.setPhysicalLocator("/customers");
        Map<String, Object> modelReaderOptions = new LinkedHashMap<String, Object>();
        modelReaderOptions.put("header", "{\"Authorization\":\"Bearer model-secret\"}");
        Map<String, Object> modelMetadata = new LinkedHashMap<String, Object>();
        modelMetadata.put("readerOptions", modelReaderOptions);
        httpModel.setTechnicalMetadata(modelMetadata);

        Map<String, Object> taskOptions = new LinkedHashMap<String, Object>();
        taskOptions.put("header", "{\"Authorization\":\"Bearer task-secret\"}");
        Map<String, Object> storedTaskOptions = securityService.prepareReaderOptionOverrides(
                taskOptions, httpModel.getTechnicalMetadata());

        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(4L);
        datasource.setName("HTTP datasource");
        datasource.setTypeCode("http");
        datasource.setTechnicalMetadata(new LinkedHashMap<String, Object>());
        DataSourceService dataSourceService = mock(DataSourceService.class);
        when(dataSourceService.get(4L)).thenReturn(datasource);
        DataModelService dataModelService = mock(DataModelService.class);
        when(dataModelService.get(40L)).thenReturn(httpModel);
        DataModelDefinition newHttpModel = new DataModelDefinition();
        newHttpModel.setId(41L);
        newHttpModel.setDatasourceId(4L);
        newHttpModel.setName("New HTTP model");
        newHttpModel.setPhysicalLocator("/new-customers");
        Map<String, Object> newReaderOptions = new LinkedHashMap<String, Object>();
        newReaderOptions.put("header", "{\"Authorization\":\"Bearer new-model-secret\"}");
        Map<String, Object> newMetadata = new LinkedHashMap<String, Object>();
        newMetadata.put("readerOptions", newReaderOptions);
        newHttpModel.setTechnicalMetadata(newMetadata);
        when(dataModelService.get(41L)).thenReturn(newHttpModel);
        PluginRuntimeOptionSchemaService runtimeSchemaService = mock(PluginRuntimeOptionSchemaService.class);
        CollectionTaskAssemblerService assemblerService = new CollectionTaskAssemblerService(
                dataSourceService, dataModelService, encryptionService, runtimeSchemaService);
        CollectionTaskScheduleMapper scheduleMapper = mock(CollectionTaskScheduleMapper.class);
        CollectionTaskService collectionTaskService = new CollectionTaskService(
                mock(CollectionTaskDefinitionMapper.class),
                mock(CollectionTaskMetricBindingMapper.class),
                scheduleMapper,
                mock(DispatchTaskMapper.class),
                mock(RunRecordMapper.class),
                dataSourceService,
                dataModelService,
                assemblerService,
                OBJECT_MAPPER,
                mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class),
                mock(DataModelLineageService.class),
                mock(DatasourceTypeCapabilityService.class));
        return new Fixture(collectionTaskService, securityService, dataModelService, httpModel, storedTaskOptions);
    }

    private CollectionTaskDefinitionEntity entityWithStoredBinding(Map<String, Object> storedTaskOptions) {
        CollectionTaskDefinitionEntity entity = new CollectionTaskDefinitionEntity();
        entity.setId(100L);
        entity.setName("HTTP task");
        entity.setTaskType(CollectionTaskType.SINGLE_TABLE.name());
        entity.setStatus(CollectionTaskStatus.ONLINE.name());
        entity.setSourceCount(1);
        entity.setSourceBindingsJson(Collections.singletonList(OBJECT_MAPPER.convertValue(
                sourceBinding(storedTaskOptions), new TypeReference<Map<String, Object>>() {
                })));
        entity.setTargetBindingJson(new LinkedHashMap<String, Object>());
        entity.setFieldMappingsJson(Collections.<Map<String, Object>>emptyList());
        entity.setExecutionOptionsJson(new LinkedHashMap<String, Object>());
        return entity;
    }

    private CollectionTaskSourceBinding sourceBinding(Map<String, Object> readerOptions) {
        CollectionTaskSourceBinding binding = new CollectionTaskSourceBinding();
        binding.setSourceAlias("src1");
        binding.setDatasourceId(4L);
        binding.setDatasourceName("HTTP datasource");
        binding.setDatasourceTypeCode("http");
        binding.setModelId(40L);
        binding.setModelName("HTTP model");
        binding.setModelPhysicalLocator("/customers");
        binding.setReaderOptions(new LinkedHashMap<String, Object>(readerOptions));
        return binding;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readerOptions(DataModelDefinition model) {
        return (Map<String, Object>) model.getTechnicalMetadata().get("readerOptions");
    }

    private static final class Fixture {
        private final CollectionTaskService collectionTaskService;
        private final HttpReaderOptionSecurityService securityService;
        private final DataModelService dataModelService;
        private final DataModelDefinition httpModel;
        private final Map<String, Object> storedTaskOptions;

        private Fixture(CollectionTaskService collectionTaskService,
                        HttpReaderOptionSecurityService securityService,
                        DataModelService dataModelService,
                        DataModelDefinition httpModel,
                        Map<String, Object> storedTaskOptions) {
            this.collectionTaskService = collectionTaskService;
            this.securityService = securityService;
            this.dataModelService = dataModelService;
            this.httpModel = httpModel;
            this.storedTaskOptions = storedTaskOptions;
        }
    }
}
