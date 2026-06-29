package com.jdragon.studio.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.WorkerLeaseMapper;
import com.jdragon.studio.infra.service.RunLogStorageService;
import com.jdragon.studio.infra.service.RunService;
import com.jdragon.studio.server.web.service.RunLogProxyService;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunLogProxySourceSlimmingRegressionTest {

    @Test
    void objectStorageLogShouldUsePointerWithoutFullRunRecord() {
        RunService runService = mock(RunService.class);
        RunLogStorageService storageService = mock(RunLogStorageService.class);
        RunLogProxyService proxyService = new RunLogProxyService(
                runService,
                mock(WorkerLeaseMapper.class),
                mock(StudioPlatformProperties.class),
                mock(RestTemplate.class),
                new ObjectMapper(),
                storageService);
        RunRecordEntity pointer = objectStoragePointer();
        RunLogView expected = new RunLogView();
        expected.setRunRecordId(88L);
        when(runService.getLogPointer(88L)).thenReturn(pointer);
        when(storageService.readObjectLog(pointer, 1, 4096, false)).thenReturn(expected);

        RunLogView result = proxyService.viewLog(88L, 1, 4096);

        assertThat(result).isSameAs(expected);
        verify(runService).getLogPointer(88L);
        verify(runService, never()).getEntity(anyLong());
    }

    @Test
    void historicalFallbackShouldReadFullRecordOnlyAfterPointerHasNoLogRoute() {
        RunService runService = mock(RunService.class);
        RunLogProxyService proxyService = new RunLogProxyService(
                runService,
                mock(WorkerLeaseMapper.class),
                mock(StudioPlatformProperties.class),
                mock(RestTemplate.class),
                new ObjectMapper(),
                mock(RunLogStorageService.class));
        RunRecordEntity pointer = new RunRecordEntity();
        pointer.setId(89L);
        pointer.setTenantId("default");
        pointer.setProjectId(100L);
        RunRecordEntity fullRecord = new RunRecordEntity();
        fullRecord.setId(89L);
        RunLogView expected = new RunLogView();
        expected.setRunRecordId(89L);
        when(runService.getLogPointer(89L)).thenReturn(pointer);
        when(runService.getEntity(89L)).thenReturn(fullRecord);
        when(runService.buildHistoricalFallback(fullRecord)).thenReturn(expected);

        RunLogView result = proxyService.viewLog(89L, 1, 4096);

        assertThat(result).isSameAs(expected);
        verify(runService).getLogPointer(89L);
        verify(runService).getEntity(89L);
        verify(runService).buildHistoricalFallback(fullRecord);
    }

    private RunRecordEntity objectStoragePointer() {
        RunRecordEntity entity = new RunRecordEntity();
        entity.setId(88L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setLogStorageType(RunLogStorageService.STORAGE_OBJECT);
        entity.setLogObjectBucket("studio-logs");
        entity.setLogObjectKey("runs/88.log");
        entity.setLogCharset("UTF-8");
        return entity;
    }
}
