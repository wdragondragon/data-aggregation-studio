package com.jdragon.studio.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.dto.enums.ModelKind;
import com.jdragon.studio.dto.model.DataModelDatasourceOptionView;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataModelListView;
import com.jdragon.studio.dto.model.DataModelOptionView;
import com.jdragon.studio.dto.model.DataModelSqlHintView;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.dto.model.RunMetricFilterOptionView;
import com.jdragon.studio.dto.model.request.DataModelSaveRequest;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.service.BusinessMetaModelMetadataService;
import com.jdragon.studio.infra.service.DataModelAccessScopeService;
import com.jdragon.studio.infra.service.DataModelIndexRebuildQueueService;
import com.jdragon.studio.infra.service.DataModelSearchIndexService;
import com.jdragon.studio.infra.service.DataModelService;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.HttpReaderOptionSecurityService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceTypeCapabilityService;
import com.jdragon.studio.infra.service.MetadataSchemaService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataModelSqlHintSourceSlimmingRegressionTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(DataModelEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DataModelEntity.class);
        }
    }

    @Test
    void sqlHintsShouldSelectOnlyColumnsNeededByEditorCompletion() {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService service = dataModelService(dataModelMapper, dataSourceService);
        when(dataModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(model()));

        List<DataModelSqlHintView> hints = service.listSqlHintsByDatasource(11L);

        assertThat(hints).hasSize(1);
        DataModelSqlHintView hint = hints.get(0);
        assertThat(hint.getName()).isEqualTo("客户经营画像表");
        assertThat(hint.getPhysicalLocator()).isEqualTo("lt_reg_customer_profile");
        assertThat(hint.getColumns()).containsExactly("customer_id", "customer_name");

        ArgumentCaptor<LambdaQueryWrapper<DataModelEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dataSourceService).assertReadableIfPresent(11L);
        verify(dataSourceService, never()).get(11L);
        verify(dataModelMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("id", "datasource_id", "name", "physical_locator", "technical_metadata")
                .doesNotContain("business_metadata", "schema_version_id", "created_at", "updated_at");
    }

    @Test
    void selectedModelSqlHintsShouldSelectOnlyColumnsNeededByFlinkQuestionCompletion() {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService service = dataModelService(dataModelMapper, dataSourceService);
        when(dataModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(model()));

        List<DataModelSqlHintView> hints = service.listSqlHintsByModelIds(Arrays.asList(21L, 21L, null, -1L));

        assertThat(hints).hasSize(1);
        assertThat(hints.get(0).getId()).isEqualTo(21L);
        assertThat(hints.get(0).getColumns()).containsExactly("customer_id", "customer_name");

        ArgumentCaptor<LambdaQueryWrapper<DataModelEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dataSourceService, never()).assertReadableIfPresent(11L);
        verify(dataModelMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("id", "datasource_id", "name", "physical_locator", "technical_metadata")
                .doesNotContain("business_metadata", "schema_version_id", "created_at", "updated_at");
        assertThat(captor.getValue().getTargetSql().toLowerCase())
                .contains("id in");
    }

    @Test
    void modelOptionsShouldSelectOnlyFieldsNeededByLineageManualRelationPicker() {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService service = dataModelService(dataModelMapper, dataSourceService);
        when(dataModelMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        when(dataModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(model()));

        PageView<DataModelOptionView> page = service.listOptions("客户", 1, 50);

        assertThat(page.getItems()).hasSize(1);
        DataModelOptionView option = page.getItems().get(0);
        assertThat(option.getId()).isEqualTo(21L);
        assertThat(option.getName()).isEqualTo("客户经营画像表");
        assertThat(page.getPageSize()).isEqualTo(50);

        ArgumentCaptor<LambdaQueryWrapper<DataModelEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dataModelMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("id", "tenant_id", "project_id", "deleted", "created_at", "updated_at", "name")
                .doesNotContain("datasource_id",
                        "model_kind",
                        "physical_locator",
                        "schema_version_id",
                        "technical_metadata",
                        "business_metadata");
    }

    @Test
    void datasourceModelOptionsShouldSelectOnlyFieldsNeededByEditorPickers() {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService service = dataModelService(dataModelMapper, dataSourceService);
        when(dataModelMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(300L);
        when(dataModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(model()));

        PageView<DataModelDatasourceOptionView> page = service.listDatasourceOptions(11L, "客户", 1, 5000);

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getPageSize()).isEqualTo(100);
        DataModelDatasourceOptionView option = page.getItems().get(0);
        assertThat(option.getId()).isEqualTo(21L);
        assertThat(option.getDatasourceId()).isEqualTo(11L);
        assertThat(option.getName()).isEqualTo("客户经营画像表");
        assertThat(option.getPhysicalLocator()).isEqualTo("lt_reg_customer_profile");

        ArgumentCaptor<LambdaQueryWrapper<DataModelEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dataSourceService).assertReadableIfPresent(11L);
        verify(dataSourceService, never()).get(11L);
        verify(dataModelMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("id", "datasource_id", "name", "model_kind", "physical_locator")
                .doesNotContain("tenant_id",
                        "project_id",
                        "deleted",
                        "created_at",
                        "updated_at",
                        "schema_version_id",
                        "technical_metadata",
                        "business_metadata");
        assertThat(captor.getValue().getTargetSql().toLowerCase())
                .contains("limit 100 offset 0");
    }

    @Test
    void modelSelectorOptionsShouldFilterByDatasourceTypeDatasourceAndKeyword() {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService service = dataModelService(dataModelMapper, dataSourceService);
        when(dataSourceService.listAccessibleIdsByType("mysql8")).thenReturn(Collections.singleton(11L));
        when(dataModelMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(300L);
        when(dataModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(model()));

        PageView<DataModelDatasourceOptionView> page = service.listSelectorOptions(
                "mysql8", 11L, null, "profile", 1, 5000);

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getPageSize()).isEqualTo(100);
        assertThat(page.getItems().get(0).getId()).isEqualTo(21L);
        assertThat(page.getItems().get(0).getDatasourceId()).isEqualTo(11L);
        verify(dataSourceService).assertReadableIfPresent(11L);
        verify(dataSourceService).listAccessibleIdsByType("mysql8");
        verify(dataSourceService, never()).get(11L);

        ArgumentCaptor<LambdaQueryWrapper<DataModelEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dataModelMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("id", "datasource_id", "name", "model_kind", "physical_locator")
                .doesNotContain("tenant_id",
                        "project_id",
                        "deleted",
                        "created_at",
                        "updated_at",
                        "schema_version_id",
                        "technical_metadata",
                        "business_metadata");
        assertThat(captor.getValue().getTargetSql().toLowerCase())
                .contains("datasource_id")
                .contains("physical_locator")
                .contains("limit 100 offset 0");
    }

    @Test
    void metricFilterModelOptionsShouldSelectOnlyFieldsNeededByQualityFilter() {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService service = dataModelService(dataModelMapper, dataSourceService);
        when(dataModelMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        when(dataModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(model()));

        PageView<RunMetricFilterOptionView> page = service.listMetricFilterOptionPage(11L, "客户", 1, 50);

        assertThat(page.getItems()).hasSize(1);
        RunMetricFilterOptionView option = page.getItems().get(0);
        assertThat(option.getId()).isEqualTo(21L);
        assertThat(option.getName()).isEqualTo("客户经营画像表");
        assertThat(option.getLabel()).isEqualTo("客户经营画像表 / lt_reg_customer_profile");
        assertThat(page.getPageSize()).isEqualTo(50);

        ArgumentCaptor<LambdaQueryWrapper<DataModelEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dataSourceService).assertReadableIfPresent(11L);
        verify(dataSourceService, never()).get(11L);
        verify(dataModelMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("id", "name", "physical_locator")
                .doesNotContain("tenant_id",
                        "project_id",
                        "deleted",
                        "created_at",
                        "updated_at",
                        "datasource_id",
                        "model_kind",
                        "schema_version_id",
                        "technical_metadata",
                        "business_metadata");
    }

    @Test
    void modelSummaryTypeFilterShouldUseDatasourceIdLookupInsteadOfFullDatasourceList() {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService service = dataModelService(dataModelMapper, dataSourceService);
        when(dataSourceService.listAccessibleIdsByType("mysql8")).thenReturn(Collections.singleton(11L));
        when(dataModelMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        when(dataModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(model()));

        PageView<DataModelListView> page = service.listSummaryPage("mysql8", 1, 20, "name", "asc");

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getName()).isEqualTo("客户经营画像表");
        verify(dataSourceService).listAccessibleIdsByType("mysql8");
        verify(dataSourceService, never()).list();

        ArgumentCaptor<LambdaQueryWrapper<DataModelEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dataModelMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("id",
                        "tenant_id",
                        "project_id",
                        "datasource_id",
                        "name",
                        "model_kind",
                        "physical_locator",
                        "schema_version_id")
                .doesNotContain("technical_metadata", "business_metadata");
        assertThat(captor.getValue().getTargetSql().toLowerCase()).contains("datasource_id");
    }

    @Test
    void datasourceModelSummaryShouldAssertReadableWithoutFullDatasourceHydration() {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService service = dataModelService(dataModelMapper, dataSourceService);
        when(dataModelMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        when(dataModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(model()));

        PageView<DataModelListView> page = service.listByDatasourceSummaryPage(11L, 1, 20, "name", "asc");

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getName()).isEqualTo("客户经营画像表");
        verify(dataSourceService).assertReadableIfPresent(11L);
        verify(dataSourceService, never()).get(11L);

        ArgumentCaptor<LambdaQueryWrapper<DataModelEntity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dataModelMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSelect())
                .contains("id",
                        "tenant_id",
                        "project_id",
                        "datasource_id",
                        "name",
                        "model_kind",
                        "physical_locator",
                        "schema_version_id")
                .doesNotContain("technical_metadata", "business_metadata");
    }

    @Test
    void datasourceModelDefinitionListShouldAssertReadableWithoutFullDatasourceHydration() {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService service = dataModelService(dataModelMapper, dataSourceService);
        when(dataModelMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        when(dataModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(model()));

        PageView<DataModelDefinition> page = service.listByDatasourcePage(11L, 1, 20, "name", "asc");

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getName()).isEqualTo("客户经营画像表");
        assertThat(page.getItems().get(0).getTechnicalMetadata()).containsKey("columns");
        verify(dataSourceService).assertReadableIfPresent(11L);
        verify(dataSourceService, never()).get(11L);
    }

    @Test
    void saveHttpModelShouldDropLegacyPushdownMappingMetadata() {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(11L);
        datasource.setTypeCode("http");
        when(dataSourceService.get(11L)).thenReturn(datasource);
        when(dataModelMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.<DataModelEntity>emptyList());
        DataModelService service = dataModelService(dataModelMapper, dataSourceService);

        Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
        Map<String, Object> readerOptions = new LinkedHashMap<String, Object>();
        readerOptions.put("params", "{\"customer_id\":\"\"}");
        readerOptions.put("header", "{\"Authorization\":\"Bearer model-secret\",\"X-Trace\":\"trace-1\"}");
        readerOptions.put("soapVersion", "SOAP_11");
        readerOptions.put("soapAction", "urn:legacy-reader-action");
        technicalMetadata.put("readerOptions", readerOptions);
        technicalMetadata.put("httpPushdownMappings", Collections.singletonList(Collections.singletonMap("field", "customer_id")));
        DataModelSaveRequest request = new DataModelSaveRequest();
        request.setDatasourceId(11L);
        request.setName("HTTP 客户风险模型");
        request.setPhysicalLocator("/risk");
        request.setModelKind(ModelKind.TABLE);
        request.setTechnicalMetadata(technicalMetadata);
        request.setBusinessMetadata(new LinkedHashMap<String, Object>());

        DataModelDefinition saved = service.save(request);

        assertThat(saved.getTechnicalMetadata())
                .containsKey("readerOptions")
                .doesNotContainKey("httpPushdownMappings");
        @SuppressWarnings("unchecked")
        Map<String, Object> savedReaderOptions =
                (Map<String, Object>) saved.getTechnicalMetadata().get("readerOptions");
        assertThat(savedReaderOptions)
                .containsKeys("params", "header")
                .doesNotContainKeys("soapVersion", "soapAction");
        assertThat(String.valueOf(savedReaderOptions.get("header")))
                .contains("Bearer model-secret")
                .contains("trace-1");
        ArgumentCaptor<DataModelEntity> captor = ArgumentCaptor.forClass(DataModelEntity.class);
        verify(dataModelMapper).insert(captor.capture());
        assertThat(captor.getValue().getTechnicalMetadata())
                .containsKey("readerOptions")
                .doesNotContainKey("httpPushdownMappings");
        @SuppressWarnings("unchecked")
        Map<String, Object> persistedReaderOptions =
                (Map<String, Object>) captor.getValue().getTechnicalMetadata().get("readerOptions");
        assertThat(persistedReaderOptions)
                .containsKeys("params", "header")
                .doesNotContainKeys("soapVersion", "soapAction");
        assertThat(String.valueOf(persistedReaderOptions.get("header")))
                .contains("ENC(")
                .doesNotContain("model-secret")
                .contains("trace-1");
        DataModelDefinition masked = service.maskSensitiveReaderOptions(saved);
        @SuppressWarnings("unchecked")
        Map<String, Object> maskedReaderOptions =
                (Map<String, Object>) masked.getTechnicalMetadata().get("readerOptions");
        assertThat(String.valueOf(maskedReaderOptions.get("header")))
                .contains("Be****et")
                .doesNotContain("model-secret")
                .contains("trace-1");
    }

    @Test
    void httpModelShouldRejectCredentialsEmbeddedInPhysicalLocator() {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(11L);
        datasource.setTypeCode("http");
        when(dataSourceService.get(11L)).thenReturn(datasource);
        when(dataModelMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.<DataModelEntity>emptyList());
        DataModelService service = dataModelService(dataModelMapper, dataSourceService);
        DataModelSaveRequest request = new DataModelSaveRequest();
        request.setDatasourceId(11L);
        request.setName("HTTP secret URL model");
        request.setPhysicalLocator("https://user:password@example.test/customers?access_token=secret");
        request.setModelKind(ModelKind.TABLE);
        request.setTechnicalMetadata(new LinkedHashMap<String, Object>());
        request.setBusinessMetadata(new LinkedHashMap<String, Object>());

        assertThatThrownBy(() -> service.save(request))
                .hasMessageContaining("Reader default parameters");

        verify(dataModelMapper, never()).insert(any(DataModelEntity.class));
    }

    @Test
    void httpModelShouldRejectCredentialsEmbeddedInTechnicalMetadataUrls() {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(11L);
        datasource.setTypeCode("http");
        when(dataSourceService.get(11L)).thenReturn(datasource);
        when(dataModelMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.<DataModelEntity>emptyList());
        DataModelService service = dataModelService(dataModelMapper, dataSourceService);
        DataModelSaveRequest request = new DataModelSaveRequest();
        request.setDatasourceId(11L);
        request.setName("HTTP metadata secret URL model");
        request.setPhysicalLocator("/customers");
        request.setModelKind(ModelKind.TABLE);
        request.setTechnicalMetadata(new LinkedHashMap<String, Object>());
        request.getTechnicalMetadata().put("requestPath",
                "https://user:password@example.test/customers?sig=actual-secret");
        request.setBusinessMetadata(new LinkedHashMap<String, Object>());

        assertThatThrownBy(() -> service.save(request))
                .hasMessageContaining("Reader default parameters");

        verify(dataModelMapper, never()).insert(any(DataModelEntity.class));
    }

    @Test
    void switchingNonHttpModelToHttpShouldNotTrustHistoricalLocator() {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataSourceDefinition httpDatasource = new DataSourceDefinition();
        httpDatasource.setId(11L);
        httpDatasource.setTypeCode("http");
        DataSourceDefinition mysqlDatasource = new DataSourceDefinition();
        mysqlDatasource.setId(12L);
        mysqlDatasource.setTypeCode("mysql8");
        when(dataSourceService.get(11L)).thenReturn(httpDatasource);
        when(dataSourceService.get(12L)).thenReturn(mysqlDatasource);
        DataModelEntity existing = model();
        existing.setDatasourceId(12L);
        existing.setPhysicalLocator("https://user:password@example.test/customers?access_token=secret");
        when(dataModelMapper.selectById(existing.getId())).thenReturn(existing);
        when(dataModelMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.<DataModelEntity>emptyList());
        DataModelService service = dataModelService(dataModelMapper, dataSourceService);
        DataModelSaveRequest request = new DataModelSaveRequest();
        request.setId(existing.getId());
        request.setDatasourceId(11L);
        request.setName(existing.getName());
        request.setPhysicalLocator(existing.getPhysicalLocator());
        request.setModelKind(ModelKind.TABLE);
        request.setTechnicalMetadata(new LinkedHashMap<String, Object>());
        request.setBusinessMetadata(new LinkedHashMap<String, Object>());

        assertThatThrownBy(() -> service.save(request))
                .hasMessageContaining("Reader default parameters");

        verify(dataModelMapper, never()).updateById(any(DataModelEntity.class));
    }

    @Test
    void publicModelViewsShouldMaskCredentialsEmbeddedInHistoricalPhysicalLocator() {
        DataModelMapper dataModelMapper = mock(DataModelMapper.class);
        DataSourceService dataSourceService = mock(DataSourceService.class);
        DataModelService service = dataModelService(dataModelMapper, dataSourceService);
        DataModelEntity entity = model();
        entity.setPhysicalLocator("https://user:password@example.test/customers?access_token=secret#debug");
        when(dataModelMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(entity));

        List<DataModelSqlHintView> hints = service.listSqlHintsByDatasource(11L);
        DataModelDefinition definition = new DataModelDefinition();
        definition.setPhysicalLocator(entity.getPhysicalLocator());
        definition.setTechnicalMetadata(new LinkedHashMap<String, Object>());
        definition.getTechnicalMetadata().put("requestPath", entity.getPhysicalLocator());
        definition.getTechnicalMetadata().put("wsdlUrl",
                "https://example.test/service?subscription-key=historical-secret");
        service.maskSensitiveReaderOptions(definition);

        assertThat(hints.get(0).getPhysicalLocator())
                .isEqualTo("https://****@example.test/customers?access_token=****#****");
        assertThat(definition.getPhysicalLocator())
                .isEqualTo("https://****@example.test/customers?access_token=****#****");
        assertThat(definition.getTechnicalMetadata().get("requestPath"))
                .isEqualTo("https://****@example.test/customers?access_token=****#****");
        assertThat(definition.getTechnicalMetadata().get("wsdlUrl"))
                .isEqualTo("https://example.test/service?subscription-key=****");
    }

    private DataModelService dataModelService(DataModelMapper dataModelMapper,
                                              DataSourceService dataSourceService) {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        when(accessService.requireCurrentProjectId()).thenReturn(100L);
        when(accessService.sharedResourceIdList(any())).thenReturn(Collections.emptyList());
        DataModelAccessScopeService scopeService = new DataModelAccessScopeService(dataModelMapper, securityService, accessService);
        return new DataModelService(
                dataModelMapper,
                dataSourceService,
                mock(MetadataSchemaService.class),
                mock(DataModelSearchIndexService.class),
                mock(DataModelIndexRebuildQueueService.class),
                mock(BusinessMetaModelMetadataService.class),
                securityService,
                accessService,
                scopeService,
                mock(DatasourceTypeCapabilityService.class),
                httpReaderOptionSecurityService());
    }

    private HttpReaderOptionSecurityService httpReaderOptionSecurityService() {
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setEncryptionSecret("http-model-reader-option-test-secret");
        return new HttpReaderOptionSecurityService(new EncryptionService(properties));
    }

    private DataModelEntity model() {
        DataModelEntity entity = new DataModelEntity();
        entity.setId(21L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setDatasourceId(11L);
        entity.setName("客户经营画像表");
        entity.setPhysicalLocator("lt_reg_customer_profile");
        Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
        technicalMetadata.put("columns", Arrays.asList(
                column("customer_id"),
                column("customer_name"),
                column("customer_id")));
        technicalMetadata.put("jdbcUrl", "jdbc:mysql://internal-host:3306/source");
        entity.setTechnicalMetadata(technicalMetadata);
        Map<String, Object> businessMetadata = new LinkedHashMap<String, Object>();
        businessMetadata.put("owner", "客户经营域");
        entity.setBusinessMetadata(businessMetadata);
        return entity;
    }

    private Map<String, Object> column(String name) {
        Map<String, Object> column = new LinkedHashMap<String, Object>();
        column.put("name", name);
        return column;
    }
}
