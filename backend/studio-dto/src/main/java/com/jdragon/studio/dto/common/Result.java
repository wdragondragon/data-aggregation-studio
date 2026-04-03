package com.jdragon.studio.dto.common;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serializable;
import java.time.LocalDateTime;

@Schema(name = "Result", description = "Unified API response wrapper")
public class Result<T> implements Serializable {
    @Schema(description = "Whether the request succeeded")
    private final boolean success;

    @Schema(description = "Business status code")
    private final String code;

    @Schema(description = "Response message")
    private final String message;

    @Schema(description = "Payload data")
    private final T data;

    @Schema(description = "Response timestamp")
    private final LocalDateTime timestamp;

    private Result(boolean success, String code, String message, T data) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = LocalDateTime.now();
    }

    public static <T> Result<T> success(T data) {
        return new Result<T>(true, StudioErrorCode.SUCCESS, "OK", data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<T>(true, StudioErrorCode.SUCCESS, message, data);
    }

    public static <T> Result<T> error(String message) {
        return new Result<T>(false, StudioErrorCode.BUSINESS_ERROR, message, null);
    }

    public static <T> Result<T> error(String code, String message) {
        return new Result<T>(false, code, message, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }
}
