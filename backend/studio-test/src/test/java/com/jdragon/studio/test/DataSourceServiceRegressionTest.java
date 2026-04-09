package com.jdragon.studio.test;

import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.request.DataSourceSaveRequest;
import com.jdragon.studio.infra.entity.DatasourceEntity;
import com.jdragon.studio.infra.mapper.DataModelMapper;
import com.jdragon.studio.infra.mapper.DatasourceMapper;
import com.jdragon.studio.infra.service.BusinessMetaModelMetadataService;
import com.jdragon.studio.infra.service.DataModelSearchIndexService;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.EncryptionService;
import com.jdragon.studio.infra.service.MetadataSchemaService;
import com.jdragon.studio.infra.service.ProjectResourceAccessService;
import com.jdragon.studio.infra.service.StudioSecurityService;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataSourceServiceRegressionTest {

    @Test
    void shouldTestConnectionUsingCurrentFormMetadataAndPreserveMaskedSensitiveValues() {
        DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);
        AggregationSourceCapabilityProvider capabilityProvider = mock(AggregationSourceCapabilityProvider.class);
        MetadataSchemaService metadataSchemaService = mock(MetadataSchemaService.class);
        EncryptionService encryptionService = mock(EncryptionService.class);
        BusinessMetaModelMetadataService businessMetaModelMetadataService = mock(BusinessMetaModelMetadataService.class);

        DatasourceEntity existing = new DatasourceEntity();
        existing.setId(11L);
        Map<String, Object> existingMetadata = new LinkedHashMap<String, Object>();
        existingMetadata.put("host", "192.168.188.128");
        existingMetadata.put("password", "ENC(cipher)");
        existing.setTechnicalMetadata(existingMetadata);

        when(datasourceMapper.selectById(eq(11L))).thenReturn(existing);
        when(metadataSchemaService.findTechnicalMetaModel(eq("mysql8"), eq("source"))).thenReturn(null);
        when(encryptionService.decrypt(eq("cipher"))).thenReturn("SecretPass123");
        when(encryptionService.mask(eq("SecretPass123"))).thenReturn("Se****23");
        when(businessMetaModelMetadataService.normalizeForDatasource(any(Map.class))).thenReturn(new LinkedHashMap<String, Object>());

        ConnectionTestResult expected = new ConnectionTestResult();
        expected.setSuccess(true);
        expected.setMessage("Connection success");
        when(capabilityProvider.testConnection(any(DataSourceDefinition.class))).thenReturn(expected);

        DataSourceService service = new DataSourceService(
                datasourceMapper,
                mock(DataModelMapper.class),
                encryptionService,
                capabilityProvider,
                metadataSchemaService,
                mock(DataModelSearchIndexService.class),
                businessMetaModelMetadataService,
                mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class)
        );

        DataSourceSaveRequest request = new DataSourceSaveRequest();
        request.setId(11L);
        request.setName("mysql_agg");
        request.setTypeCode("mysql8");
        request.setEnabled(true);
        request.setExecutable(true);
        Map<String, Object> requestMetadata = new LinkedHashMap<String, Object>();
        requestMetadata.put("host", "192.168.188.129");
        requestMetadata.put("password", "Se****23");
        request.setTechnicalMetadata(requestMetadata);
        request.setBusinessMetadata(new LinkedHashMap<String, Object>());

        ConnectionTestResult actual = service.testConnection(request);

        assertThat(actual).isSameAs(expected);

        ArgumentCaptor<DataSourceDefinition> captor = ArgumentCaptor.forClass(DataSourceDefinition.class);
        verify(capabilityProvider).testConnection(captor.capture());
        assertThat(captor.getValue().getTechnicalMetadata())
                .containsEntry("host", "192.168.188.129")
                .containsEntry("password", "ENC(cipher)");
    }
}
