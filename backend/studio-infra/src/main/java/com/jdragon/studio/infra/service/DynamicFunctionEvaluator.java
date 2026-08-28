package com.jdragon.studio.infra.service;

import com.jdragon.aggregation.commons.util.DynamicFunctionResolver;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;

import java.time.LocalDateTime;
import java.util.List;

/** Studio adapter for the shared dynamic function implementation. */
public final class DynamicFunctionEvaluator {

    private DynamicFunctionEvaluator() {
    }

    public static String replaceAll(String content) {
        return replaceAll(content, LocalDateTime.now());
    }

    public static String replaceAll(String content, LocalDateTime baseTime) {
        try {
            return DynamicFunctionResolver.replaceAll(content, baseTime, DynamicFunctionResolver.Mode.STRICT);
        } catch (RuntimeException exception) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    public static List<String> validate(String content) {
        return DynamicFunctionResolver.validate(content, DynamicFunctionResolver.Mode.STRICT);
    }
}
