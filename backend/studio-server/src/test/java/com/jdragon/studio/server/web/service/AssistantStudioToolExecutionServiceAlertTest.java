package com.jdragon.studio.server.web.service;

import com.jdragon.studio.dto.model.PageView;
import com.jdragon.studio.infra.service.AlertChannelService;
import com.jdragon.studio.infra.service.AlertDeliveryService;
import com.jdragon.studio.infra.service.AlertIncidentService;
import com.jdragon.studio.infra.service.AlertRuleService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AssistantStudioToolExecutionServiceAlertTest {

    @Test
    void eventsViewShouldReturnIncidentTimelineInsteadOfIncidentList() {
        AssistantStudioToolExecutionService service = mock(AssistantStudioToolExecutionService.class, CALLS_REAL_METHODS);
        AlertIncidentService incidentService = mock(AlertIncidentService.class);
        ReflectionTestUtils.setField(service, "alertRuleService", mock(AlertRuleService.class));
        ReflectionTestUtils.setField(service, "alertIncidentService", incidentService);
        ReflectionTestUtils.setField(service, "alertChannelService", mock(AlertChannelService.class));
        ReflectionTestUtils.setField(service, "alertDeliveryService", mock(AlertDeliveryService.class));
        PageView<?> expected = PageView.of(2, 5, 0L, java.util.Collections.emptyList());
        org.mockito.Mockito.when(incidentService.events(10L, 2, 5)).thenReturn((PageView) expected);

        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("incidentId", 10L);
        params.put("pageNo", 2);
        params.put("pageSize", 5);
        Object result = ReflectionTestUtils.invokeMethod(service, "executeAlertList", "events", params);

        assertSame(expected, result);
        verify(incidentService).events(10L, 2, 5);
    }
}
