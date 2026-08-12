package com.jdragon.studio.server.web.advice;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.io.IOException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(StudioException.class)
    public ResponseEntity<Result<Void>> handleStudioException(StudioException ex) {
        log.warn("Studio business exception: code={}, message={}", ex.getCode(), ex.getMessage(), ex);
        HttpStatus status = HttpStatus.BAD_REQUEST;
        if (StudioErrorCode.UNAUTHORIZED.equals(ex.getCode())) {
            status = HttpStatus.UNAUTHORIZED;
        } else if (StudioErrorCode.FORBIDDEN.equals(ex.getCode())) {
            status = HttpStatus.FORBIDDEN;
        } else if (StudioErrorCode.NOT_FOUND.equals(ex.getCode())) {
            status = HttpStatus.NOT_FOUND;
        } else if (StudioErrorCode.SERVICE_UNAVAILABLE.equals(ex.getCode())) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
        } else if (StudioErrorCode.INTERNAL_SERVER_ERROR.equals(ex.getCode())) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return ResponseEntity.status(status).body(Result.error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> handleAuthenticationException(AuthenticationException ex) {
        log.warn("Authentication exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.error(StudioErrorCode.UNAUTHORIZED, ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Validation exception: {}", message, ex);
        return ResponseEntity.badRequest().body(Result.error(StudioErrorCode.BAD_REQUEST, message));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<Void>> handleBindException(BindException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("Bind exception: {}", message, ex);
        return ResponseEntity.badRequest().body(Result.error(StudioErrorCode.BAD_REQUEST, message));
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            ConstraintViolationException.class
    })
    public ResponseEntity<Result<Void>> handleRequestException(Exception ex) {
        log.warn("Invalid request: {}", ex.getMessage(), ex);
        return ResponseEntity.badRequest()
                .body(Result.error(StudioErrorCode.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Result<Void>> handleMethodValidationException(
            HandlerMethodValidationException ex) {
        if (ex.isForReturnValue()) {
            log.error("Invalid controller return value", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Result.error(StudioErrorCode.INTERNAL_SERVER_ERROR, ex.getMessage()));
        }
        log.warn("Invalid request: {}", ex.getMessage(), ex);
        return ResponseEntity.badRequest()
                .body(Result.error(StudioErrorCode.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage(), ex);
        return ResponseEntity.badRequest().body(Result.error(StudioErrorCode.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsableException(AsyncRequestNotUsableException ex) {
        log.debug("Streaming client disconnected: {}", ex.getMessage());
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Result<Void>> handleAsyncRequestTimeoutException(
            AsyncRequestTimeoutException ex, HttpServletResponse response) {
        if (response.isCommitted() || isEventStream(response.getContentType())) {
            log.debug("Streaming request timed out or disconnected: {}", ex.getMessage());
            return null;
        }
        log.warn("Async request timed out: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.error(StudioErrorCode.SERVICE_UNAVAILABLE, ex.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Result<Void>> handleIOException(IOException ex, HttpServletResponse response) {
        if (response.isCommitted() || isEventStream(response.getContentType())) {
            log.debug("Streaming client disconnected: {}", ex.getMessage());
            return null;
        }
        log.error("Unhandled server I/O exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(StudioErrorCode.INTERNAL_SERVER_ERROR, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpectedException(Exception ex) {
        log.error("Unhandled server exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(StudioErrorCode.INTERNAL_SERVER_ERROR, ex.getMessage()));
    }

    private boolean isEventStream(String contentType) {
        return contentType != null && contentType.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
    }
}
