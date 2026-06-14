package com.jdragon.studio.commons.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import com.jdragon.studio.commons.constant.StudioConstants;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class StudioCapturedMdcDenyFilter extends Filter<ILoggingEvent> {

    private static final List<String> CAPTURED_MDC_KEYS = Arrays.asList(
            StudioConstants.MDC_OPEN_SERVICE_INVOCATION_LOG_ID,
            StudioConstants.MDC_DATA_INGESTION_REQUEST_ID,
            StudioConstants.MDC_RUN_LOG_ID
    );

    @Override
    public FilterReply decide(ILoggingEvent event) {
        Map<String, String> mdc = getMdc(event);
        if (mdc == null || mdc.isEmpty()) {
            return FilterReply.NEUTRAL;
        }
        for (String key : CAPTURED_MDC_KEYS) {
            if (hasText(mdc.get(key))) {
                return FilterReply.DENY;
            }
        }
        return FilterReply.NEUTRAL;
    }

    private Map<String, String> getMdc(ILoggingEvent event) {
        if (event == null) {
            return null;
        }
        try {
            return event.getMDCPropertyMap();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
