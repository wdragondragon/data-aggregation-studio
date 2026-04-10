package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FieldMappingRuleApiRegressionTest extends StudioApiRegressionTestSupport {

    @Test
    void shouldCreateListReadOptionsAndDeleteFieldMappingRule() throws Exception {
        String authorization = adminAuthorizationHeader();

        JsonNode saved = saveRule(authorization, rulePayload("Phone Normalize", "规整", "phone_normalize", true,
                param("pattern", 1, "input", null, "Regex pattern"),
                param("trim", 2, "checkbox", "[\"true\",\"false\"]", "Trim value"),
                param("mode", 3, "radioGroup", "[\"strict\",\"loose\"]", "Normalize mode")));
        Long ruleId = saved.path("data").path("id").asLong();
        assertThat(ruleId).isPositive();

        mockMvc.perform(get("/api/v1/field-mapping-rules")
                        .header("Authorization", authorization)
                        .param("keyword", "phone_normalize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].mappingName").value("Phone Normalize"))
                .andExpect(jsonPath("$.data.items[0].mappingCode").value("phone_normalize"));

        mockMvc.perform(get("/api/v1/field-mapping-rules/{id}", ruleId)
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mappingType").value("规整"))
                .andExpect(jsonPath("$.data.params.length()").value(3))
                .andExpect(jsonPath("$.data.params[0].paramName").value("pattern"))
                .andExpect(jsonPath("$.data.params[2].componentType").value("radioGroup"))
                .andExpect(jsonPath("$.data.params[2].paramValueJson").value("[\"strict\",\"loose\"]"));

        saveRule(authorization, rulePayload("Date Format", "规整", "date_format", false,
                param("format", 1, "input", null, "Format text")));

        mockMvc.perform(get("/api/v1/field-mapping-rules/options")
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].mappingCode").value("phone_normalize"));

        mockMvc.perform(delete("/api/v1/field-mapping-rules/{id}", ruleId)
                        .header("Authorization", authorization))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/field-mapping-rules")
                        .header("Authorization", authorization)
                        .param("keyword", "phone_normalize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void shouldRejectDuplicateMappingCodeAndDuplicateRuleParamDefinitions() throws Exception {
        String authorization = adminAuthorizationHeader();
        saveRule(authorization, rulePayload("Mask Phone", "脱敏", "mask_phone", true,
                param("mask", 1, "input", null, "Mask pattern")));

        mockMvc.perform(post("/api/v1/field-mapping-rules")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rulePayload("Mask Phone Copy", "脱敏", "mask_phone", true,
                                param("mask", 1, "input", null, "Mask pattern")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        mockMvc.perform(post("/api/v1/field-mapping-rules")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rulePayload("Bad Param Rule", "规整", "bad_param_rule", true,
                                param("leftPad", 1, "input", null, "Pad left"),
                                param("leftPad", 2, "input", null, "Pad left again")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        mockMvc.perform(post("/api/v1/field-mapping-rules")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rulePayload("Bad Order Rule", "规整", "bad_order_rule", true,
                                param("leftPad", 1, "input", null, "Pad left"),
                                param("rightPad", 1, "input", null, "Pad right")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void shouldRejectUnsupportedMappingType() throws Exception {
        String authorization = adminAuthorizationHeader();

        mockMvc.perform(post("/api/v1/field-mapping-rules")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rulePayload("Bad Type Rule", "string_clean", "bad_type_rule", true,
                                param("value", 1, "input", null, "Value")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void shouldRejectNonStringOptionParameterValues() throws Exception {
        String authorization = adminAuthorizationHeader();

        mockMvc.perform(post("/api/v1/field-mapping-rules")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rulePayload("Bad Select Rule", "过滤", "bad_select_rule", true,
                                param("saveOrDelete", 1, "select", "[{\"label\":\"save\",\"value\":\"save\"}]", "Option values")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        mockMvc.perform(post("/api/v1/field-mapping-rules")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(rulePayload("Bad Checkbox Rule", "过滤", "bad_checkbox_rule", true,
                                param("trim", 1, "checkbox", "[true,false]", "Option values")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    private JsonNode saveRule(String authorization, Map<String, Object> payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/field-mapping-rules")
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return readBody(result);
    }

    private Map<String, Object> rulePayload(String mappingName,
                                            String mappingType,
                                            String mappingCode,
                                            boolean enabled,
                                            Map<String, Object>... params) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("mappingName", mappingName);
        payload.put("mappingType", mappingType);
        payload.put("mappingCode", mappingCode);
        payload.put("enabled", Boolean.valueOf(enabled));
        payload.put("description", mappingName + " description");
        List<Map<String, Object>> paramList = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> param : params) {
            paramList.add(param);
        }
        payload.put("params", paramList);
        return payload;
    }

    private Map<String, Object> param(String paramName,
                                      int paramOrder,
                                      String componentType,
                                      String paramValueJson,
                                      String description) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("paramName", paramName);
        payload.put("paramOrder", Integer.valueOf(paramOrder));
        payload.put("componentType", componentType);
        payload.put("paramValueJson", paramValueJson);
        payload.put("description", description);
        return payload;
    }
}
