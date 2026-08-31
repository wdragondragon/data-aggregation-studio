package com.jdragon.studio.infra.service;

import com.jdragon.studio.dto.enums.CollectionTaskType;
import com.jdragon.studio.dto.model.CollectionTaskDefinitionView;
import com.jdragon.studio.dto.model.CollectionTaskSourceBinding;
import com.jdragon.studio.dto.model.CollectionTaskTargetBinding;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.FieldMappingDefinition;
import org.mockito.Mockito;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectionTaskAssemblerServiceKafkaConfigTest {

    @Test
    void shouldNormalizeKafkaClientPropertyAliasesForJobPlugins() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("bootstrap.servers", "localhost:9092");
        raw.put("group.id", "compat-group");
        raw.put("topic", "compat-topic");
        raw.put("kerberos", false);

        Map<String, Object> normalized = CollectionTaskAssemblerService.normalizeKafkaJobConfig(raw);

        assertThat(normalized)
                .containsEntry("bootstrapServers", "localhost:9092")
                .containsEntry("kerberos", false)
                .doesNotContainKeys("bootstrap.servers", "group.id", "groupId", "topic");
    }

    @Test
    void shouldKeepCamelCasePropertiesWhenDatasourceAlreadyUsesPluginNames() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("bootstrapServers", "localhost:9092");
        raw.put("groupId", "compat-group");

        Map<String, Object> normalized = CollectionTaskAssemblerService.normalizeKafkaJobConfig(raw);

        assertThat(normalized).containsEntry("bootstrapServers", "localhost:9092")
                .doesNotContainKey("groupId");
    }

    @Test
    void shouldNormalizeLegacyBrokerAliasForJobPlugins() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("brokers", "legacy-broker:9092");
        raw.put("consumerGroup", "legacy-group");

        Map<String, Object> normalized = CollectionTaskAssemblerService.normalizeKafkaJobConfig(raw);

        assertThat(normalized).containsEntry("bootstrapServers", "legacy-broker:9092")
                .doesNotContainKeys("brokers", "consumerGroup", "groupId");
    }

    @Test
    void shouldNormalizeHistoricalKafkaConnectionAliasesWithoutOverridingCanonicalValues() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("bootstrapServers", "historical-broker:9092");
        raw.put("brokers", "older-broker:9092");
        raw.put("userName", "historical-user");

        Map<String, Object> normalized = CollectionTaskAssemblerService.normalizeKafkaJobConfig(raw);

        assertThat(normalized)
                .containsEntry("bootstrapServers", "historical-broker:9092")
                .containsEntry("username", "historical-user")
                .doesNotContainKeys("brokers", "userName");

        raw.put("bootstrap.servers", "canonical-broker:9092");
        raw.put("username", "canonical-user");
        normalized = CollectionTaskAssemblerService.normalizeKafkaJobConfig(raw);

        assertThat(normalized)
                .containsEntry("bootstrapServers", "canonical-broker:9092")
                .containsEntry("username", "canonical-user");
    }

    @Test
    void datasourceSaveAndConnectionTestShouldUseCanonicalKafkaConnectionMetadata() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("bootstrapServers", "historical-broker:9092");
        raw.put("brokers", "older-broker:9092");
        raw.put("userName", "historical-user");
        raw.put("topic", "legacy-topic");
        raw.put("queue", "legacy-queue");
        raw.put("consumerGroup", "legacy-group");
        raw.put("tag", "legacy-tag");
        raw.put("offsetReset", "earliest");
        raw.put("batchSize", 999);
        raw.put("ack", "all");
        raw.put("otherProperties", Map.of("enable.auto.commit", "true"));

        Map<String, Object> normalized =
                DataSourceService.normalizeDatasourceConnectionMetadata("kafka", raw);

        assertThat(normalized)
                .containsEntry("bootstrap.servers", "historical-broker:9092")
                .containsEntry("username", "historical-user")
                .doesNotContainKeys("bootstrapServers", "brokers", "userName",
                        "topic", "queue", "consumerGroup", "tag", "offsetReset",
                        "batchSize", "ack", "otherProperties");
    }

    @Test
    void shouldIgnoreNullInputWithoutCreatingNullKafkaProperties() {
        assertThat(CollectionTaskAssemblerService.normalizeKafkaJobConfig(null)).isEmpty();
    }

    @Test
    void taskRuntimeOptionsCannotOverrideKafkaDatasourceConnection() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("bootstrapServers", "wrong-broker:9092");
        raw.put("security.protocol", "PLAINTEXT");
        raw.put("username", "wrong-user");
        raw.put("groupId", "task-group");
        raw.put("offsetReset", "earliest");
        raw.put("otherProperties", Map.of("compression.type", "zstd"));

        Map<String, Object> normalized = KafkaConfigurationSupport.normalizeTaskRuntimeOptions(raw);

        assertThat(normalized)
                .containsEntry("groupId", "task-group")
                .containsEntry("offsetReset", "earliest")
                .containsKey("otherProperties")
                .doesNotContainKeys("bootstrapServers", "security.protocol", "username");
    }

    @Test
    void shouldAssembleKafkaReaderOptionsFromSourceBindingAndModelTopic() {
        DataSourceService datasourceService = Mockito.mock(DataSourceService.class);
        DataModelService modelService = Mockito.mock(DataModelService.class);
        PluginRuntimeOptionSchemaService schemaService = Mockito.mock(PluginRuntimeOptionSchemaService.class);
        DataSourceDefinition datasource = kafkaDatasource();
        DataModelDefinition model = kafkaModel(2L, "model-topic");
        Mockito.when(datasourceService.getInternal(1L)).thenReturn(datasource);
        Mockito.when(datasourceService.getInternal(2L)).thenReturn(datasource);
        Mockito.when(modelService.get(10L)).thenReturn(model);
        Mockito.when(modelService.get(11L)).thenReturn(model);
        Mockito.when(schemaService.resolvePluginType("kafka", "reader")).thenReturn("kafka");
        Mockito.when(schemaService.resolvePluginType("kafka", "writer")).thenReturn("kafka");
        Mockito.when(schemaService.reservedKeys("reader")).thenReturn(List.of("topic", "columns"));
        Mockito.when(schemaService.reservedKeys("writer")).thenReturn(List.of("topic", "columns"));

        CollectionTaskAssemblerService service = new CollectionTaskAssemblerService(
                datasourceService, modelService, Mockito.mock(EncryptionService.class), schemaService);
        CollectionTaskDefinitionView definition = kafkaDefinition(10L, 11L);
        Map<String, Object> readerOptions = new LinkedHashMap<>();
        readerOptions.put("groupId", "task-group");
        readerOptions.put("offsetReset", "earliest");
        readerOptions.put("resetOffset", Boolean.TRUE);
        readerOptions.put("pollTimeoutMs", 5000);
        readerOptions.put("batchSize", 200);
        readerOptions.put("bootstrapServers", "wrong-reader-broker:9092");
        definition.getSourceBindings().get(0).setReaderOptions(readerOptions);
        Map<String, Object> writerOptions = new LinkedHashMap<>();
        writerOptions.put("ack", "all");
        writerOptions.put("batchSize", 100);
        writerOptions.put("bootstrapServers", "wrong-writer-broker:9092");
        definition.getTargetBinding().setWriterOptions(writerOptions);

        Map<String, Object> assembled = service.assemble(definition);
        Map<String, Object> reader = castMap(assembled.get("reader"));
        Map<String, Object> readerConfig = castMap(reader.get("config"));
        Map<String, Object> writer = castMap(assembled.get("writer"));
        Map<String, Object> writerConfig = castMap(writer.get("config"));

        assertThat(readerConfig).containsEntry("topic", "model-topic")
                .containsEntry("groupId", "task-group")
                .containsEntry("offsetReset", "earliest")
                .containsEntry("resetOffset", Boolean.TRUE)
                .containsEntry("pollTimeoutMs", 5000)
                .containsEntry("batchSize", 200)
                .containsEntry("bootstrapServers", "broker:9092")
                .doesNotContainKey("group.id");
        assertThat(writerConfig).containsEntry("topic", "model-topic")
                .containsEntry("ack", "all")
                .containsEntry("batchSize", 100)
                .containsEntry("bootstrapServers", "broker:9092");
    }

    @Test
    void shouldRejectKafkaAssemblyWhenModelTopicIsMissing() {
        DataSourceService datasourceService = Mockito.mock(DataSourceService.class);
        DataModelService modelService = Mockito.mock(DataModelService.class);
        PluginRuntimeOptionSchemaService schemaService = Mockito.mock(PluginRuntimeOptionSchemaService.class);
        DataSourceDefinition datasource = kafkaDatasource();
        DataModelDefinition model = kafkaModel(2L, null);
        Mockito.when(datasourceService.getInternal(1L)).thenReturn(datasource);
        Mockito.when(datasourceService.getInternal(2L)).thenReturn(datasource);
        Mockito.when(modelService.get(10L)).thenReturn(model);
        Mockito.when(modelService.get(11L)).thenReturn(model);
        Mockito.when(schemaService.resolvePluginType("kafka", "reader")).thenReturn("kafka");
        Mockito.when(schemaService.resolvePluginType("kafka", "writer")).thenReturn("kafka");
        Mockito.when(schemaService.reservedKeys("reader")).thenReturn(List.of("topic", "columns"));
        Mockito.when(schemaService.reservedKeys("writer")).thenReturn(List.of("topic", "columns"));

        CollectionTaskAssemblerService service = new CollectionTaskAssemblerService(
                datasourceService, modelService, Mockito.mock(EncryptionService.class), schemaService);
        assertThatThrownBy(() -> service.assemble(kafkaDefinition(10L, 11L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("physicalLocator");
    }

    private CollectionTaskDefinitionView kafkaDefinition(Long sourceModelId, Long targetModelId) {
        CollectionTaskDefinitionView definition = new CollectionTaskDefinitionView();
        definition.setTaskType(CollectionTaskType.SINGLE_TABLE);
        CollectionTaskSourceBinding source = new CollectionTaskSourceBinding();
        source.setSourceAlias("src1");
        source.setDatasourceId(1L);
        source.setModelId(sourceModelId);
        definition.setSourceBindings(List.of(source));
        CollectionTaskTargetBinding target = new CollectionTaskTargetBinding();
        target.setDatasourceId(2L);
        target.setModelId(targetModelId);
        definition.setTargetBinding(target);
        FieldMappingDefinition mapping = new FieldMappingDefinition();
        mapping.setSourceAlias("src1");
        mapping.setSourceField("payload");
        mapping.setTargetField("payload");
        definition.setFieldMappings(List.of(mapping));
        return definition;
    }

    private DataSourceDefinition kafkaDatasource() {
        DataSourceDefinition datasource = new DataSourceDefinition();
        datasource.setId(1L);
        datasource.setTypeCode("kafka");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("bootstrap.servers", "broker:9092");
        metadata.put("topic", "legacy-topic");
        metadata.put("group.id", "legacy-group");
        datasource.setTechnicalMetadata(metadata);
        return datasource;
    }

    private DataModelDefinition kafkaModel(Long id, String topic) {
        DataModelDefinition model = new DataModelDefinition();
        model.setId(id);
        model.setName("events");
        model.setPhysicalLocator(topic);
        model.setTechnicalMetadata(new LinkedHashMap<String, Object>(Map.of(
                "columns", List.of(Map.of("name", "payload")))));
        return model;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
