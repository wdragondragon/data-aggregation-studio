package com.jdragon.studio.flink.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.FlinkQuestionPlanView;
import com.jdragon.studio.dto.model.request.FlinkQuestionAskRequest;
import com.jdragon.studio.flink.service.FlinkQuestionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FlinkQueryControllerTest {

    @Test
    void standaloneFlinkServiceShouldExposePlanningOnly() throws Exception {
        FlinkQuestionService questionService = mock(FlinkQuestionService.class);
        FlinkQuestionPlanView plan = new FlinkQuestionPlanView();
        plan.setRuntimeClusterId(50L);
        plan.setSql("SELECT * FROM m_7 LIMIT 100");
        plan.setModelIds(List.of(7L));
        when(questionService.plan(any(FlinkQuestionAskRequest.class))).thenReturn(plan);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new FlinkQueryController(questionService)).build();
        ObjectMapper objectMapper = new ObjectMapper();
        FlinkQuestionAskRequest request = new FlinkQuestionAskRequest();
        request.setRuntimeClusterId(50L);
        request.setQuestion("查询模型");

        mockMvc.perform(post("/api/flink/question/plan")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runtimeClusterId").value(50))
                .andExpect(jsonPath("$.data.sql").value("SELECT * FROM m_7 LIMIT 100"));

        mockMvc.perform(post("/api/flink/sql/execute")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/flink/question/ask")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
