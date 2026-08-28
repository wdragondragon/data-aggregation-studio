package com.jdragon.studio.infra.service;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
                .containsEntry("topic", "compat-topic")
                .containsEntry("kerberos", false)
                .doesNotContainKeys("bootstrap.servers", "group.id", "groupId");
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
    void shouldIgnoreNullInputWithoutCreatingNullKafkaProperties() {
        assertThat(CollectionTaskAssemblerService.normalizeKafkaJobConfig(null)).isEmpty();
    }
}
