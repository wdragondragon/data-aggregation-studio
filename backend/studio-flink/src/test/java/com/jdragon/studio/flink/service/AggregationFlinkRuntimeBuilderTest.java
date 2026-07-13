package com.jdragon.studio.flink.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.flink.connector.AggregationFlinkTableRuntime;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.service.EncryptionService;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AggregationFlinkRuntimeBuilderTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void soapContractShouldNotBeOverriddenByReaderDefaults() throws Exception {
        DataSourceDefinition datasource = httpDatasource();
        DataModelDefinition model = httpModel();
        model.getTechnicalMetadata().put("protocolMode", "SOAP");
        model.getTechnicalMetadata().put("soapVersion", "SOAP_12");
        model.getTechnicalMetadata().put("soapAction", "urn:model-action");
        Map<String, Object> readerOptions = new LinkedHashMap<String, Object>();
        readerOptions.put("soapVersion", "SOAP_11");
        readerOptions.put("soapAction", "urn:stale-reader-action");
        readerOptions.put("requestBody", "<Envelope/>");
        model.getTechnicalMetadata().put("readerOptions", readerOptions);

        String configJson = newBuilder().build(datasource, model, null)
                .getDataSourceDTO().getExtraParams().get("__studio_http_reader_config");
        Map<String, Object> config = OBJECT_MAPPER.readValue(configJson,
                new TypeReference<Map<String, Object>>() {
                });

        assertEquals("SOAP_12", config.get("soapVersion"));
        assertEquals("urn:model-action", config.get("soapAction"));
        assertEquals("application/soap+xml;charset=UTF-8", config.get("contentType"));
    }

    @Test
    void incompleteBusinessStatusContractShouldFailFast() {
        DataModelDefinition model = httpModel();
        model.getTechnicalMetadata().put("businessStatusPath", "code");

        assertThrows(IllegalArgumentException.class,
                () -> newBuilder().build(httpDatasource(), model, null));
    }

    @Test
    void restXmlProtocolShouldDriveReaderResultType() throws Exception {
        DataModelDefinition model = httpModel();
        model.getTechnicalMetadata().put("protocolMode", "REST_XML");
        model.getTechnicalMetadata().put("resultType", "json");
        model.getTechnicalMetadata().put("readerOptions", new LinkedHashMap<String, Object>(
                Collections.<String, Object>singletonMap("contentType", "application/json;charset=utf-8")));

        String configJson = newBuilder().build(httpDatasource(), model, null)
                .getDataSourceDTO().getExtraParams().get("__studio_http_reader_config");
        Map<String, Object> config = OBJECT_MAPPER.readValue(configJson,
                new TypeReference<Map<String, Object>>() {
                });

        assertEquals("REST_XML", config.get("protocolMode"));
        assertEquals("xml", config.get("resultType"));
        assertEquals("application/xml;charset=UTF-8", config.get("contentType"));
    }

    @Test
    void httpRequestPathShouldBeUsedWhenPhysicalLocatorIsBlank() throws Exception {
        DataModelDefinition model = httpModel();
        model.setPhysicalLocator(null);
        model.getTechnicalMetadata().put("physicalName", "legacy-customers");
        model.getTechnicalMetadata().put("requestPath", "/v2/customers/{customer_id}");

        AggregationFlinkTableRuntime runtime = newBuilder().build(httpDatasource(), model, null);
        String configJson = runtime.getDataSourceDTO().getExtraParams().get("__studio_http_reader_config");
        Map<String, Object> config = OBJECT_MAPPER.readValue(configJson,
                new TypeReference<Map<String, Object>>() {
                });

        assertEquals("/v2/customers/{customer_id}", runtime.getPhysicalLocator());
        assertEquals("http://127.0.0.1:19090/v2/customers/{customer_id}", config.get("url"));
    }

    @Test
    void httpModelNameShouldNotBeAppendedWhenNoRequestPathIsConfigured() throws Exception {
        DataModelDefinition model = httpModel();
        model.setPhysicalLocator(null);

        AggregationFlinkTableRuntime runtime = newBuilder().build(httpDatasource(), model, null);
        String configJson = runtime.getDataSourceDTO().getExtraParams().get("__studio_http_reader_config");
        Map<String, Object> config = OBJECT_MAPPER.readValue(configJson,
                new TypeReference<Map<String, Object>>() {
                });

        assertEquals(null, runtime.getPhysicalLocator());
        assertEquals("http://127.0.0.1:19090", config.get("url"));
    }

    private AggregationFlinkRuntimeBuilder newBuilder() {
        return new AggregationFlinkRuntimeBuilder(new EncryptionService(new StudioPlatformProperties()));
    }

    private DataSourceDefinition httpDatasource() {
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(1L);
        datasource.setName("HTTP");
        datasource.setTypeCode("http");
        datasource.setTechnicalMetadata(new LinkedHashMap<String, Object>(
                Collections.<String, Object>singletonMap("url", "http://127.0.0.1:19090")));
        return datasource;
    }

    private DataModelDefinition httpModel() {
        DataModelDefinition model = new DataModelDefinition();
        model.setId(2L);
        model.setDatasourceId(1L);
        model.setName("customers");
        model.setPhysicalLocator("/customers");
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("columns", Collections.singletonList(
                Collections.<String, Object>singletonMap("name", "customer_id")));
        model.setTechnicalMetadata(metadata);
        return model;
    }
}
