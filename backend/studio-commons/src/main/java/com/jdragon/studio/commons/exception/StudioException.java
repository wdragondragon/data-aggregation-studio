package com.jdragon.studio.commons.exception;

public class StudioException extends RuntimeException {
    private final String code;

    public StudioException(String message) {
        this(StudioErrorCode.BUSINESS_ERROR, message);
    }

    public StudioException(String code, String message) {
        super(message);
        this.code = code;
    }

    public StudioException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
