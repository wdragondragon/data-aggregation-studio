package com.jdragon.studio.server.web.advice;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class GlobalExceptionHandlerTest {

    @Test
    void shouldLogExpectedBusinessExceptionWithoutStackTrace() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new GlobalExceptionHandler().handleStudioException(new StudioException(
                    StudioErrorCode.CONFLICT, "Target path already exists"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("code=CONFLICT")
                    .contains("Target path already exists");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void shouldRetainStackTraceForInternalStudioException() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new GlobalExceptionHandler().handleStudioException(new StudioException(
                    StudioErrorCode.INTERNAL_SERVER_ERROR, "Worker execution failed",
                    new IllegalStateException("SFTP channel closed")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).contains("code=INTERNAL_SERVER_ERROR")
                    .contains("Worker execution failed");
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

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

    @Test
    void shouldIgnoreAsyncStreamingClientDisconnect() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        assertThatCode(() -> handler.handleAsyncRequestNotUsableException(
                new AsyncRequestNotUsableException("Response not usable after client disconnect")))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldNotWriteErrorBodyForEventStreamTimeout() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);

        var result = handler.handleAsyncRequestTimeoutException(
                new AsyncRequestTimeoutException(), response);

        assertThat(result).isNull();
    }

    @Test
    void shouldReturnServiceUnavailableForOrdinaryAsyncTimeout() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletResponse response = new MockHttpServletResponse();

        var result = handler.handleAsyncRequestTimeoutException(
                new AsyncRequestTimeoutException(), response);

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getCode()).isEqualTo(StudioErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void shouldNotWriteErrorBodyForEventStreamIOException() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);

        var result = handler.handleIOException(new IOException("Connection aborted"), response);

        assertThat(result).isNull();
    }

    @Test
    void shouldNotWriteErrorBodyAfterResponseIsCommitted() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);

        var result = handler.handleIOException(new IOException("Connection reset"), response);

        assertThat(result).isNull();
    }

    @Test
    void shouldPreserveInternalServerErrorForOrdinaryIOException() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletResponse response = new MockHttpServletResponse();

        var result = handler.handleIOException(new IOException("Read failed"), response);

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getCode()).isEqualTo(StudioErrorCode.INTERNAL_SERVER_ERROR);
        assertThat(result.getBody().getMessage()).isEqualTo("Read failed");
    }
}
