package com.jdragon.studio.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonConfigTest {

    @Test
    void localDateTimeRequestAcceptsStudioAndIsoSeparators() throws Exception {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new JacksonConfig().longToStringCustomizer().customize(builder);
        ObjectMapper objectMapper = builder.build();

        RequestWithTime studioRequest = objectMapper.readValue(
                "{\"startedAt\":\"2026-07-11 02:08:57\"}", RequestWithTime.class);
        RequestWithTime isoRequest = objectMapper.readValue(
                "{\"startedAt\":\"2026-07-11T02:08:57\"}", RequestWithTime.class);

        LocalDateTime expected = LocalDateTime.of(2026, 7, 11, 2, 8, 57);
        assertThat(studioRequest.startedAt).isEqualTo(expected);
        assertThat(isoRequest.startedAt).isEqualTo(expected);
    }

    public static class RequestWithTime {
        public LocalDateTime startedAt;
    }
}
