package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jdragon.studio.server.web.controller.AssistantToolController;
import com.jdragon.studio.server.web.service.AssistantStudioToolExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssistantToolApiRegressionTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private MockMvc mockMvc;
    private AssistantStudioToolExecutionService toolExecutionService;

    @BeforeEach
    void setUp() {
        toolExecutionService = mock(AssistantStudioToolExecutionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AssistantToolController(toolExecutionService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void executeToolShouldReturnBackendToolResultEnvelope() throws Exception {
        when(toolExecutionService.execute(any())).thenReturn(toolResult());

        mockMvc.perform(post("/api/v1/assistant/tools/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"interfaceCode\":\"studio.feature.list\",\"params\":{\"path\":\"/datasources\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.schema").value("studio.tool-result.v1"))
                .andExpect(jsonPath("$.data.interfaceCode").value("studio.feature.list"))
                .andExpect(jsonPath("$.data.path").value("/datasources"))
                .andExpect(jsonPath("$.data.executedBy").value("backend"))
                .andExpect(jsonPath("$.data.mutation").value(false))
                .andExpect(jsonPath("$.data.data.items[0].name").value("mysql1"));
    }

    private Map<String, Object> toolResult() {
        Map<String, Object> datasource = new LinkedHashMap<String, Object>();
        datasource.put("id", 1L);
        datasource.put("name", "mysql1");
        Map<String, Object> page = new LinkedHashMap<String, Object>();
        page.put("items", Collections.singletonList(datasource));
        page.put("total", 1L);

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("schema", "studio.tool-result.v1");
        result.put("interfaceCode", "studio.feature.list");
        result.put("path", "/datasources");
        result.put("executedBy", "backend");
        result.put("mutation", Boolean.FALSE);
        result.put("data", page);
        return result;
    }
}
