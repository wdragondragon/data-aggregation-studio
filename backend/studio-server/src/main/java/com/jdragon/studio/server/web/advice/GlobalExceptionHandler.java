package com.jdragon.studio.server.web.advice;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
        }
        return ResponseEntity.status(status).body(Result.error(ex.getCode(), ex.getMessage()));
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage(), ex);
        return ResponseEntity.badRequest().body(Result.error(StudioErrorCode.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpectedException(Exception ex) {
        log.error("Unhandled server exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.error(StudioErrorCode.INTERNAL_SERVER_ERROR, ex.getMessage()));
    }
}
