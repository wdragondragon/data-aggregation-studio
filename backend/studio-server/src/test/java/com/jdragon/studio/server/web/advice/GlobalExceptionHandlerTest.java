package com.jdragon.studio.server.web.advice;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void shouldMapServiceUnavailableStatus() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleStudioException(new StudioException(
                StudioErrorCode.SERVICE_UNAVAILABLE, "Target runtime cluster is unavailable"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(StudioErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void shouldPreserveInternalServerErrorStatus() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleStudioException(new StudioException(
                StudioErrorCode.INTERNAL_SERVER_ERROR, "Worker execution failed"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(StudioErrorCode.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getMessage()).isEqualTo("Worker execution failed");
    }

    @Test
    void shouldMapMissingRequiredQueryParameterToBadRequest() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleRequestException(
                new MissingServletRequestParameterException("runtimeClusterId", "Long"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(StudioErrorCode.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).contains("runtimeClusterId");
    }
}
