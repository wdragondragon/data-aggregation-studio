package com.jdragon.studio.test;

import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationStreamSecurityRegressionTest extends StudioApiRegressionTestSupport {

    @Test
    void notificationStreamShouldStillRequireJwtOnInitialRequest() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedNotificationStreamShouldStartAsyncWithoutForbiddenResponse() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/stream")
                        .header(HttpHeaders.AUTHORIZATION, adminAuthorizationHeader())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }
}
