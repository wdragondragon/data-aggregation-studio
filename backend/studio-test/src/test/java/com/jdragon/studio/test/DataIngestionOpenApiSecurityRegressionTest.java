package com.jdragon.studio.test;

import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DataIngestionOpenApiSecurityRegressionTest extends StudioApiRegressionTestSupport {

    @Test
    void shouldAllowOpenIngestionEndpointWithoutJwt() throws Exception {
        mockMvc.perform(post("/openapi/data-ingestion-services/missing/key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Data ingestion service is not available"));
    }
}
