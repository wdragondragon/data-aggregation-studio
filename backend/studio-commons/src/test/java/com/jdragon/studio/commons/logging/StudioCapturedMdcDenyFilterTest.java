package com.jdragon.studio.commons.logging;

import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.spi.FilterReply;
import com.jdragon.studio.commons.constant.StudioConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class StudioCapturedMdcDenyFilterTest {

    private final StudioCapturedMdcDenyFilter filter = new StudioCapturedMdcDenyFilter();

    @Test
    void allowsApplicationLogsWithoutCapturedMdc() {
        assertThat(filter.decide(new LoggingEvent())).isEqualTo(FilterReply.NEUTRAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            StudioConstants.MDC_OPEN_SERVICE_INVOCATION_LOG_ID,
            StudioConstants.MDC_DATA_INGESTION_REQUEST_ID,
            StudioConstants.MDC_RUN_LOG_ID
    })
    void deniesLogsCapturedByDedicatedArchives(String mdcKey) {
        LoggingEvent event = new LoggingEvent();
        event.setMDCPropertyMap(Collections.singletonMap(mdcKey, "captured-id"));

        assertThat(filter.decide(event)).isEqualTo(FilterReply.DENY);
    }
}
