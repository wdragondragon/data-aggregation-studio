package com.jdragon.studio.test;

import com.jdragon.studio.infra.service.HttpReaderOptionNormalizer;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpReaderOptionNormalizerTest {

    @Test
    void shouldNormalizeStructuredValuesSkipBlankValuesAndProtectReservedKeys() {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("url", "http://127.0.0.1/base");
        config.put("mode", "GET");
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        options.put("url", "http://malicious/override");
        options.put("pageSize", "");
        options.put("pageRead", Boolean.TRUE);
        options.put("header", map("Authorization", "Bearer value"));
        options.put("params", map("customer_id", "C001"));
        options.put("requestBody", map("filter", map("status", "ACTIVE")));

        HttpReaderOptionNormalizer.mergeInto(config, options);

        assertThat(config)
                .containsEntry("url", "http://127.0.0.1/base")
                .containsEntry("mode", "GET")
                .containsEntry("pageRead", Boolean.TRUE)
                .doesNotContainKey("pageSize");
        assertThat(String.valueOf(config.get("header"))).isEqualTo("{\"Authorization\":\"Bearer value\"}");
        assertThat(String.valueOf(config.get("params"))).isEqualTo("{\"customer_id\":\"C001\"}");
        assertThat(String.valueOf(config.get("requestBody"))).contains("\"status\":\"ACTIVE\"");
    }

    @Test
    void shouldRejectNonObjectHeaderAndParams() {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        options.put("header", "[]");

        assertThatThrownBy(() -> HttpReaderOptionNormalizer.mergeInto(config, options))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header");
    }

    private Map<String, Object> map(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put(key, value);
        return result;
    }
}
