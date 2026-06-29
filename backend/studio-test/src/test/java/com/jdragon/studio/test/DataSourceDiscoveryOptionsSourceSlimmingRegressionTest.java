package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.enums.ModelKind;
import com.jdragon.studio.dto.model.DataModelDatasourceOptionView;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryOptionResult;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.service.BusinessMetaModelMetadataService;
import com.jdragon.studio.infra.service.DataModelIndexRebuildQueueService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceConnectionFingerprintService;
import com.jdragon.studio.infra.service.DatasourceConnectionHealthService;
import com.jdragon.studio.infra.service.DatasourceTypeCapabilityService;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.MetadataSchemaService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataSourceDiscoveryOptionsSourceSlimmingRegressionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void discoveryOptionsShouldUseSlimProviderResultWithoutModelMetadata() throws Exception {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        AggregationSourceCapabilityProvider capabilityProvider = mock(AggregationSourceCapabilityProvider.class);
        MetadataSchemaService metadataSchemaService = mock(MetadataSchemaService.class);
        DataSourceService service = dataSourceService(datasourceMapper, capabilityProvider, metadataSchemaService);
        when(datasourceMapper.selectById(11L)).thenReturn(datasource());
        when(metadataSchemaService.listSchemas()).thenReturn(Collections.emptyList());
        when(capabilityProvider.discoverModelOptions(any(DataSourceDefinition.class), eq("客户"), eq(1), eq(20)))
                .thenReturn(optionResult());

        ModelDiscoveryOptionResult result = service.discoverModelOptions(11L, "客户", 1, 20);

        assertThat(result.getModels()).hasSize(1);
        assertThat(result.getModels().get(0).getName()).isEqualTo("客户经营画像表");
        String responseJson = objectMapper.writeValueAsString(result);
        assertThat(responseJson)
                .contains("客户经营画像表", "lt_reg_customer_profile")
                .doesNotContain("technicalMetadata", "businessMetadata", "password", "columns");

        ArgumentCaptor<DataSourceDefinition> datasourceCaptor = ArgumentCaptor.forClass(DataSourceDefinition.class);
        verify(capabilityProvider).discoverModelOptions(datasourceCaptor.capture(), eq("客户"), eq(1), eq(20));
        assertThat(datasourceCaptor.getValue().getTechnicalMetadata()).containsKey("password");
        verify(capabilityProvider, never()).discoverModels(any(DataSourceDefinition.class), any(), any(), any());
    }

    private DataSourceService dataSourceService(DatasourceMapper datasourceMapper,
                                                AggregationSourceCapabilityProvider capabilityProvider,
                                                MetadataSchemaService metadataSchemaService) {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        when(accessService.sharedResourceIdList(any())).thenReturn(Collections.emptyList());
        return new DataSourceService(
                datasourceMapper,
                mock(DataModelMapper.class),
                mock(EncryptionService.class),
                capabilityProvider,
                metadataSchemaService,
                mock(DataModelIndexRebuildQueueService.class),
                mock(BusinessMetaModelMetadataService.class),
                securityService,
                accessService,
                mock(DatasourceTypeCapabilityService.class),
                mock(DatasourceConnectionFingerprintService.class),
                mock(DatasourceConnectionHealthService.class));
    }

    private DatasourceEntity datasource() {
        DatasourceEntity entity = new DatasourceEntity();
        entity.setId(11L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setName("长期回归-客户经营敏感数据源");
        entity.setTypeCode("mysql8");
        entity.setEnabled(Integer.valueOf(1));
        entity.setExecutable(Integer.valueOf(1));
        Map<String, Object> technicalMetadata = new LinkedHashMap<String, Object>();
        technicalMetadata.put("host", "127.0.0.1");
        technicalMetadata.put("port", "3306");
        technicalMetadata.put("database", "studio_longterm_regression");
        technicalMetadata.put("userName", "root");
        technicalMetadata.put("password", "S77-sensitive-password");
        entity.setTechnicalMetadata(technicalMetadata);
        entity.setBusinessMetadata(Collections.singletonMap("owner", "客户经营域"));
        return entity;
    }

    private ModelDiscoveryOptionResult optionResult() {
        DataModelDatasourceOptionView option = new DataModelDatasourceOptionView();
        option.setDatasourceId(11L);
        option.setName("客户经营画像表");
        option.setModelKind(ModelKind.TABLE);
        option.setPhysicalLocator("lt_reg_customer_profile");

        ModelDiscoveryOptionResult result = new ModelDiscoveryOptionResult();
        result.setModels(Collections.singletonList(option));
        result.setTotal(1L);
        result.setPageNo(1);
        result.setPageSize(20);
        result.setMessage("Discovered RDBMS objects");
        return result;
    }
}
