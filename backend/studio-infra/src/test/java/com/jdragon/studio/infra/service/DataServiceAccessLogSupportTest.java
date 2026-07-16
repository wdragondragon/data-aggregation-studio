package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.entity.DataServiceDefinitionEntity;
import com.jdragon.studio.infra.entity.DataServiceAccessLogEntity;
import com.jdragon.studio.infra.mapper.DataServiceAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessLogMapper;
import com.jdragon.studio.infra.model.AlertSignal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DataServiceAccessLogSupportTest {

    @Test
    void shouldPublishOnlyLogArchiveSignalForDataServiceAccess() {
        DataServiceAccessLogMapper accessLogMapper = mock(DataServiceAccessLogMapper.class);
        DataServiceAccessCounterMapper counterMapper = mock(DataServiceAccessCounterMapper.class);
        AlertSignalPublisher signalPublisher = mock(AlertSignalPublisher.class);
        DataServiceAccessLogSupport support = new DataServiceAccessLogSupport(
                accessLogMapper, counterMapper, new DataServiceInvocationSupport());
        support.setAlertSignalPublisher(signalPublisher);
        DataServiceDefinitionEntity service = new DataServiceDefinitionEntity();
        service.setId(30L);
        service.setTenantId("default");
        service.setProjectId(20L);
        service.setServiceCode("orders");
        service.setServiceName("orders api");
        OpenServiceInvocationLogService.ArchiveResult archiveResult =
                new OpenServiceInvocationLogService.ArchiveResult();
        archiveResult.setLogArchiveStatus("FAILED");
        archiveResult.setLogArchiveError("upload failed");

        support.recordAccessLog(service, null, "default", "request-1", "POST",
                LocalDateTime.now(), System.nanoTime(), false, 500, "FAILED", "request failed",
                "system log", "127.0.0.1", "test", false, false, 0L, archiveResult);

        ArgumentCaptor<AlertSignal> captor = ArgumentCaptor.forClass(AlertSignal.class);
        verify(signalPublisher).publish(captor.capture());
        assertEquals("LOG_ARCHIVE", captor.getValue().getSignalType());
        assertEquals("DATA_SERVICE_LOG", captor.getValue().getSubjectKey());
        verify(accessLogMapper).insert(any(DataServiceAccessLogEntity.class));
    }
}
