package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.studio.dto.enums.DataIngestionSourcePosition;
import com.jdragon.studio.dto.enums.DataIngestionStatus;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.model.DataIngestionFieldMapping;
import com.jdragon.studio.dto.model.DataIngestionInvokeResult;
import com.jdragon.studio.dto.model.DataIngestionServiceView;
import com.jdragon.studio.dto.model.DataIngestionSourceBinding;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.DataIngestionAccessLogEntity;
import com.jdragon.studio.infra.mapper.DataIngestionAccessCounterMapper;
import com.jdragon.studio.infra.mapper.DataIngestionAccessLogMapper;
import com.jdragon.studio.infra.mapper.DataIngestionServiceMapper;
import com.jdragon.studio.infra.mapper.DataIngestionSubscriptionMapper;
import com.jdragon.studio.infra.mapper.DataServiceAccessLogMapper;
import com.jdragon.studio.infra.mapper.ProtocolConversionAccessLogMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataIngestionAccessLogSupportTest {

    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(DataIngestionAccessLogEntity.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DataIngestionAccessLogEntity.class);
        }
    }

    @Test
    void shouldCaptureUnifiedInvocationAndDataAggregationThreadLogs() throws InterruptedException {
        Long jobId = Long.valueOf(2026070101L);
        String requestId = "request-open-log-" + jobId;
        OpenServiceInvocationLogSupport logSupport = new OpenServiceInvocationLogSupport();
        OpenServiceInvocationLogSupport.LogScope scope = logSupport.open(requestId,
                OpenServiceInvocationLogService.DOMAIN_DATA_INGESTION_SERVICES,
                jobId);
        try {
            OpenServiceInvocationLogSupport.withMdc(requestId, new Runnable() {
                @Override
                public void run() {
                    LoggerFactory.getLogger(DataIngestionExecutionSupport.class)
                            .info("Starting data ingestion JobContainer requestId={}, jobId={}", requestId, jobId);
                }
            });
            Thread writerThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    LoggerFactory.getLogger("com.jdragon.aggregation.test")
                            .info("DataAggregation writer handled Alice secretKey=plain-secret");
                }
            }, "DataAggregation-Thread-writer-" + jobId);
            writerThread.start();
            writerThread.join();
        } finally {
            scope.close();
        }

        Map<String, String> sectionContents = scope.sectionContents();
        String capturedLog = scope.content();
        assertTrue(capturedLog.contains("Starting data ingestion JobContainer"));
        assertTrue(capturedLog.contains("DataAggregation-Thread-writer-" + jobId));
        assertTrue(capturedLog.contains("Alice"));
        assertTrue(capturedLog.contains("secretKey=******"));
        assertFalse(capturedLog.contains("plain-secret"));
        assertTrue(sectionContents.isEmpty());
    }

    @Test
    void shouldCaptureTargetLogsIntoSeparateSections() throws InterruptedException {
        Long firstJobId = Long.valueOf(2026070102L);
        Long secondJobId = Long.valueOf(2026070103L);
        String requestId = "request-open-log-sections";
        OpenServiceInvocationLogSupport logSupport = new OpenServiceInvocationLogSupport();
        OpenServiceInvocationLogSupport.LogScope scope = logSupport.open(requestId,
                OpenServiceInvocationLogService.DOMAIN_DATA_INGESTION_SERVICES,
                null);
        try {
            scope.registerSection("target_1_orders_" + firstJobId, firstJobId);
            scope.registerSection("target_2_customers_" + secondJobId, secondJobId);
            Thread firstWriter = new Thread(new Runnable() {
                @Override
                public void run() {
                    LoggerFactory.getLogger("com.jdragon.aggregation.test")
                            .info("orders target wrote Alice secretKey=plain-secret-one");
                }
            }, "DataAggregation-Thread-writer-" + firstJobId);
            Thread secondWriter = new Thread(new Runnable() {
                @Override
                public void run() {
                    LoggerFactory.getLogger("com.jdragon.aggregation.test")
                            .info("customers target wrote Bob secretKey=plain-secret-two");
                }
            }, "DataAggregation-Thread-writer-" + secondJobId);
            firstWriter.start();
            secondWriter.start();
            firstWriter.join();
            secondWriter.join();
        } finally {
            scope.close();
        }

        Map<String, String> sectionContents = scope.sectionContents();
        String firstLog = sectionContents.get("target_1_orders_" + firstJobId);
        String secondLog = sectionContents.get("target_2_customers_" + secondJobId);
        String mainLog = scope.content();
        assertFalse(mainLog.contains("orders target wrote Alice"));
        assertFalse(mainLog.contains("customers target wrote Bob"));
        assertTrue(firstLog.contains("Alice"));
        assertFalse(firstLog.contains("Bob"));
        assertTrue(firstLog.contains("secretKey=******"));
        assertFalse(firstLog.contains("plain-secret-one"));
        assertTrue(secondLog.contains("Bob"));
        assertFalse(secondLog.contains("Alice"));
        assertTrue(secondLog.contains("secretKey=******"));
        assertFalse(secondLog.contains("plain-secret-two"));
    }

    @Test
    void shouldExecuteTargetsConcurrentlyAndKeepSourceResultOrder() {
        ConcurrentFailingAssembler assembler = new ConcurrentFailingAssembler(2);
        DataIngestionExecutionSupport executionSupport = new DataIngestionExecutionSupport(
                assembler,
                new ObjectMapper());
        DataIngestionServiceView service = new DataIngestionServiceView();
        service.setStatus(DataIngestionStatus.ONLINE);
        service.setServiceCode("parallel_test");
        service.setMaxBatchSize(Integer.valueOf(10));
        Map<String, Object> body = mapOf("data", mapOf(
                "orders", Arrays.asList(mapOf("name", "Alice")),
                "customers", Arrays.asList(mapOf("name", "Bob"))));

        DataIngestionInvokeResult result = executionSupport.executeBindings(service,
                Arrays.asList(binding("orders", "data.orders"), binding("customers", "data.customers")),
                new LinkedHashMap<String, Object>(),
                new LinkedHashMap<String, Object>(),
                new LinkedHashMap<String, Object>(),
                body,
                "request-parallel",
                Long.valueOf(2026070201L),
                null,
                false);

        assertTrue(assembler.maxActive() >= 2, "Targets should execute concurrently instead of waiting for each target serially");
        assertEquals("FAILED", result.getStatus());
        assertEquals(2, result.getSourceResults().size());
        assertEquals("orders", result.getSourceResults().get(0).getSourceCode());
        assertEquals("customers", result.getSourceResults().get(1).getSourceCode());
        assertNotEquals(Long.valueOf(2026070201L), result.getSourceResults().get(0).getJobId());
        assertNotEquals(Long.valueOf(2026070202L), result.getSourceResults().get(1).getJobId());
        assertNotEquals(result.getSourceResults().get(0).getJobId(), result.getSourceResults().get(1).getJobId());
        assertTrue(result.getSourceResults().get(0).getLogSectionKey().contains("orders"));
        assertTrue(result.getSourceResults().get(1).getLogSectionKey().contains("customers"));
        assertEquals(Long.valueOf(2L), result.getReceivedCount());
        assertEquals(Long.valueOf(2L), result.getFailedCount());
    }

    @Test
    void shouldReadDataIngestionTargetSectionFromFallbackLog() {
        DataIngestionAccessLogMapper ingestionMapper = mock(DataIngestionAccessLogMapper.class);
        OpenServiceInvocationLogService service = invocationLogService(ingestionMapper);
        when(ingestionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(
                accessLogPointer(),
                accessLogFallback(sectionedLog()),
                accessLogPointer(),
                accessLogFallback(sectionedLog()));

        RunLogView fullLog = service.viewLog(OpenServiceInvocationLogService.DOMAIN_DATA_INGESTION_SERVICES, 77L, 1, 4096);
        RunLogView targetLog = service.viewLogSection(OpenServiceInvocationLogService.DOMAIN_DATA_INGESTION_SERVICES,
                77L,
                "target_1_orders_2026070201",
                1,
                4096);

        assertEquals(2, fullLog.getSections().size());
        assertEquals("orders", fullLog.getSections().get(0).getSourceCode());
        assertEquals("SUCCESS", fullLog.getSections().get(0).getStatus());
        assertEquals(Long.valueOf(2026070201L), fullLog.getSections().get(0).getJobId());
        assertTrue(targetLog.getContent().contains("orders target wrote Alice"));
        assertFalse(targetLog.getContent().contains("customers target wrote Bob"));
        assertTrue(targetLog.getDownloadName().contains("target_1_orders_2026070201"));
    }

    @Test
    void shouldDownloadDataIngestionTargetSectionFromObjectArchive() {
        DataIngestionAccessLogMapper ingestionMapper = mock(DataIngestionAccessLogMapper.class);
        RunLogObjectStore objectStore = mock(RunLogObjectStore.class);
        OpenServiceInvocationLogService service = invocationLogService(ingestionMapper, objectStore);
        when(ingestionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(accessLogObjectPointer());
        stubObjectDownload(objectStore,
                "studio-log-bucket",
                "studio/invocation/sectioned.log",
                sectionedLog());

        RunLogView targetLog = service.downloadLogSection(OpenServiceInvocationLogService.DOMAIN_DATA_INGESTION_SERVICES,
                78L,
                "target_2_customers_2026070202");

        assertFalse(targetLog.isHistoricalFallback());
        assertEquals(2, targetLog.getSections().size());
        assertTrue(targetLog.getContent().contains("customers target wrote Bob"));
        assertFalse(targetLog.getContent().contains("orders target wrote Alice"));
    }

    @Test
    void shouldArchiveDataIngestionTargetLogToDerivedObjectKey() {
        DataIngestionAccessLogMapper ingestionMapper = mock(DataIngestionAccessLogMapper.class);
        RunLogObjectStore objectStore = mock(RunLogObjectStore.class);
        CloudObjectStorageService cloudObjectStorageService = mock(CloudObjectStorageService.class);
        when(cloudObjectStorageService.bucketConfigured()).thenReturn(true);
        when(cloudObjectStorageService.resolveBucket()).thenReturn("studio-log-bucket");
        OpenServiceInvocationLogService service = objectArchiveInvocationLogService(ingestionMapper, objectStore, cloudObjectStorageService);

        OpenServiceInvocationLogService.ArchiveResult result = service.archiveDataIngestionTargetLog(
                "studio/invocation/data-ingestion-request.log",
                "target_1_orders_2026070201",
                targetObjectLog("target_1_orders_2026070201", "orders", "orders target wrote Alice"));

        String expectedObjectKey = "studio/invocation/data-ingestion-request/targets/target_1_orders_2026070201.log";
        ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(objectStore).put(eq("studio-log-bucket"),
                eq(expectedObjectKey),
                contentCaptor.capture(),
                eq("text/plain;charset=UTF-8"));
        assertEquals(OpenServiceInvocationLogService.ARCHIVE_AVAILABLE, result.getLogArchiveStatus());
        assertEquals(expectedObjectKey, result.getLogObjectKey());
        assertTrue(new String(contentCaptor.getValue(), StandardCharsets.UTF_8).contains("orders target wrote Alice"));
    }

    @Test
    void shouldDeleteDataIngestionTargetObjectsBeforeMainArchiveObject() {
        DataIngestionAccessLogMapper ingestionMapper = mock(DataIngestionAccessLogMapper.class);
        RunLogObjectStore objectStore = mock(RunLogObjectStore.class);
        OpenServiceInvocationLogService service = invocationLogService(ingestionMapper, objectStore);
        stubObjectDownload(objectStore,
                "studio-log-bucket",
                "studio/invocation/sectioned.log",
                objectIndexedLog(false));

        boolean deleted = service.deleteDataIngestionArchivedObjects(Long.valueOf(78L),
                "studio-log-bucket",
                "studio/invocation/sectioned.log",
                StandardCharsets.UTF_8.name(),
                OpenServiceInvocationLogService.ARCHIVE_AVAILABLE);

        assertTrue(deleted);
        InOrder inOrder = inOrder(objectStore);
        inOrder.verify(objectStore).delete("studio-log-bucket", "studio/invocation/sectioned/targets/target_1_orders_2026070201.log");
        inOrder.verify(objectStore).delete("studio-log-bucket", "studio/invocation/sectioned/targets/target_2_customers_2026070202.log");
        inOrder.verify(objectStore).delete("studio-log-bucket", "studio/invocation/sectioned.log");
    }

    @Test
    void shouldPurgeDataIngestionAccessLogRowsAfterArchivedObjectsDeleted() {
        DataIngestionAccessLogMapper ingestionMapper = mock(DataIngestionAccessLogMapper.class);
        OpenServiceInvocationLogService invocationLogService = mock(OpenServiceInvocationLogService.class);
        DataIngestionMetricsService metricsService = new DataIngestionMetricsService(ingestionMapper,
                mock(DataIngestionAccessCounterMapper.class),
                mock(DataIngestionServiceMapper.class),
                mock(DataIngestionSubscriptionMapper.class),
                mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class),
                invocationLogService);
        DataIngestionAccessLogEntity expired = accessLogObjectPointer();
        when(ingestionMapper.selectExpiredArchivePointers(any(LocalDateTime.class), eq(200), any(List.class)))
                .thenReturn(Arrays.asList(expired), Collections.<DataIngestionAccessLogEntity>emptyList());
        when(invocationLogService.deleteDataIngestionArchivedObjects(expired.getId(),
                expired.getLogObjectBucket(),
                expired.getLogObjectKey(),
                expired.getLogCharset(),
                expired.getLogArchiveStatus())).thenReturn(true);
        when(ingestionMapper.deleteExpiredByIds(eq(Arrays.asList(expired.getId())))).thenReturn(1);

        int deleted = metricsService.purgeExpiredAccessLogs(90);

        assertEquals(1, deleted);
        verify(ingestionMapper).deleteExpiredByIds(eq(Arrays.asList(expired.getId())));
    }

    @Test
    void shouldKeepDataIngestionAccessLogRowsWhenArchivedObjectsCannotBeDeleted() {
        DataIngestionAccessLogMapper ingestionMapper = mock(DataIngestionAccessLogMapper.class);
        OpenServiceInvocationLogService invocationLogService = mock(OpenServiceInvocationLogService.class);
        DataIngestionMetricsService metricsService = new DataIngestionMetricsService(ingestionMapper,
                mock(DataIngestionAccessCounterMapper.class),
                mock(DataIngestionServiceMapper.class),
                mock(DataIngestionSubscriptionMapper.class),
                mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class),
                invocationLogService);
        DataIngestionAccessLogEntity expired = accessLogObjectPointer();
        when(ingestionMapper.selectExpiredArchivePointers(any(LocalDateTime.class), eq(200), any(List.class)))
                .thenReturn(Arrays.asList(expired), Collections.<DataIngestionAccessLogEntity>emptyList());
        when(invocationLogService.deleteDataIngestionArchivedObjects(expired.getId(),
                expired.getLogObjectBucket(),
                expired.getLogObjectKey(),
                expired.getLogCharset(),
                expired.getLogArchiveStatus())).thenReturn(false);

        int deleted = metricsService.purgeExpiredAccessLogs(90);

        assertEquals(0, deleted);
        verify(ingestionMapper, never()).deleteExpiredByIds(any(List.class));
    }

    @Test
    void shouldContinuePurgingAfterSkippedDataIngestionArchiveDeletion() {
        DataIngestionAccessLogMapper ingestionMapper = mock(DataIngestionAccessLogMapper.class);
        OpenServiceInvocationLogService invocationLogService = mock(OpenServiceInvocationLogService.class);
        DataIngestionMetricsService metricsService = new DataIngestionMetricsService(ingestionMapper,
                mock(DataIngestionAccessCounterMapper.class),
                mock(DataIngestionServiceMapper.class),
                mock(DataIngestionSubscriptionMapper.class),
                mock(StudioSecurityService.class),
                mock(ProjectResourceAccessService.class),
                invocationLogService);
        DataIngestionAccessLogEntity blocked = accessLogObjectPointer();
        blocked.setId(Long.valueOf(78L));
        DataIngestionAccessLogEntity deletable = accessLogObjectPointer();
        deletable.setId(Long.valueOf(79L));
        when(ingestionMapper.selectExpiredArchivePointers(any(LocalDateTime.class), eq(200), any(List.class)))
                .thenReturn(Arrays.asList(blocked),
                        Arrays.asList(deletable),
                        Collections.<DataIngestionAccessLogEntity>emptyList());
        when(invocationLogService.deleteDataIngestionArchivedObjects(blocked.getId(),
                blocked.getLogObjectBucket(),
                blocked.getLogObjectKey(),
                blocked.getLogCharset(),
                blocked.getLogArchiveStatus())).thenReturn(false);
        when(invocationLogService.deleteDataIngestionArchivedObjects(deletable.getId(),
                deletable.getLogObjectBucket(),
                deletable.getLogObjectKey(),
                deletable.getLogCharset(),
                deletable.getLogArchiveStatus())).thenReturn(true);
        when(ingestionMapper.deleteExpiredByIds(eq(Arrays.asList(deletable.getId())))).thenReturn(1);

        int deleted = metricsService.purgeExpiredAccessLogs(90);

        assertEquals(1, deleted);
        verify(ingestionMapper).deleteExpiredByIds(eq(Arrays.asList(deletable.getId())));
    }

    @Test
    void shouldReadDataIngestionTargetSectionsFromObjectIndex() {
        DataIngestionAccessLogMapper ingestionMapper = mock(DataIngestionAccessLogMapper.class);
        RunLogObjectStore objectStore = mock(RunLogObjectStore.class);
        OpenServiceInvocationLogService service = invocationLogService(ingestionMapper, objectStore);
        when(ingestionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(accessLogObjectPointer());
        stubObjectDownload(objectStore,
                "studio-log-bucket",
                "studio/invocation/sectioned.log",
                objectIndexedLog(false));

        RunLogView fullLog = service.viewLog(OpenServiceInvocationLogService.DOMAIN_DATA_INGESTION_SERVICES, 78L, 1, 4096);

        assertEquals(2, fullLog.getSections().size());
        assertEquals("orders", fullLog.getSections().get(0).getSourceCode());
        assertEquals("SUCCESS", fullLog.getSections().get(0).getStatus());
        assertEquals(OpenServiceInvocationLogService.ARCHIVE_AVAILABLE, fullLog.getSections().get(0).getArchiveStatus());
        assertEquals(Long.valueOf(2026070201L), fullLog.getSections().get(0).getJobId());
    }

    @Test
    void shouldPreferTargetObjectWhenReadingDataIngestionSection() {
        DataIngestionAccessLogMapper ingestionMapper = mock(DataIngestionAccessLogMapper.class);
        RunLogObjectStore objectStore = mock(RunLogObjectStore.class);
        OpenServiceInvocationLogService service = invocationLogService(ingestionMapper, objectStore);
        when(ingestionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(accessLogObjectPointer());
        stubObjectDownload(objectStore,
                "studio-log-bucket",
                "studio/invocation/sectioned.log",
                objectIndexedLog(false));
        stubObjectDownload(objectStore,
                "studio-log-bucket",
                "studio/invocation/sectioned/targets/target_1_orders_2026070201.log",
                targetObjectLog("target_1_orders_2026070201", "orders", "orders target wrote Alice"));

        RunLogView targetLog = service.downloadLogSection(OpenServiceInvocationLogService.DOMAIN_DATA_INGESTION_SERVICES,
                78L,
                "target_1_orders_2026070201");

        assertFalse(targetLog.isHistoricalFallback());
        assertEquals(2, targetLog.getSections().size());
        assertTrue(targetLog.getContent().contains("orders target wrote Alice"));
        assertFalse(targetLog.getContent().contains("customers target wrote Bob"));
    }

    @Test
    void shouldMergeTargetObjectsWhenDownloadingDataIngestionFullLog() {
        DataIngestionAccessLogMapper ingestionMapper = mock(DataIngestionAccessLogMapper.class);
        RunLogObjectStore objectStore = mock(RunLogObjectStore.class);
        OpenServiceInvocationLogService service = invocationLogService(ingestionMapper, objectStore);
        when(ingestionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(accessLogObjectPointer());
        stubObjectDownload(objectStore,
                "studio-log-bucket",
                "studio/invocation/sectioned.log",
                objectIndexedLog(false));
        stubObjectDownload(objectStore,
                "studio-log-bucket",
                "studio/invocation/sectioned/targets/target_1_orders_2026070201.log",
                targetObjectLog("target_1_orders_2026070201", "orders", "orders target wrote Alice"));
        stubObjectDownload(objectStore,
                "studio-log-bucket",
                "studio/invocation/sectioned/targets/target_2_customers_2026070202.log",
                targetObjectLog("target_2_customers_2026070202", "customers", "customers target wrote Bob"));

        RunLogView fullLog = service.downloadLog(OpenServiceInvocationLogService.DOMAIN_DATA_INGESTION_SERVICES, 78L);

        assertTrue(fullLog.getContent().contains(OpenServiceInvocationLogService.DATA_INGESTION_TARGET_LOG_START_PREFIX
                + "target_1_orders_2026070201"));
        assertTrue(fullLog.getContent().contains("orders target wrote Alice"));
        assertTrue(fullLog.getContent().contains("customers target wrote Bob"));
        assertEquals(2, fullLog.getSections().size());
    }

    @Test
    void shouldFallbackToInlineSectionWhenTargetObjectIsUnavailable() {
        DataIngestionAccessLogMapper ingestionMapper = mock(DataIngestionAccessLogMapper.class);
        RunLogObjectStore objectStore = mock(RunLogObjectStore.class);
        OpenServiceInvocationLogService service = invocationLogService(ingestionMapper, objectStore);
        when(ingestionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(accessLogObjectPointer());
        stubObjectDownload(objectStore,
                "studio-log-bucket",
                "studio/invocation/sectioned.log",
                objectIndexedLog(true));

        RunLogView targetLog = service.downloadLogSection(OpenServiceInvocationLogService.DOMAIN_DATA_INGESTION_SERVICES,
                78L,
                "target_2_customers_2026070202");

        assertTrue(targetLog.getContent().contains("customers target wrote Bob"));
        assertFalse(targetLog.getContent().contains("orders target wrote Alice"));
        assertEquals(OpenServiceInvocationLogService.ARCHIVE_FAILED, targetLog.getSections().get(1).getArchiveStatus());
    }

    @Test
    void shouldPersistFullInvocationFallbackWhenArchiveIsUnavailable() {
        DataIngestionAccessLogMapper accessLogMapper = mock(DataIngestionAccessLogMapper.class);
        DataIngestionAccessLogSupport support = new DataIngestionAccessLogSupport(accessLogMapper,
                mock(DataIngestionAccessCounterMapper.class));
        String summary = "Invocation Summary" + System.lineSeparator()
                + "[requestId] request-fallback";
        String fallback = summary + System.lineSeparator()
                + "Request Headers" + System.lineSeparator()
                + "{\"X-Data-Ingestion-Token\":\"plain-token\"}" + System.lineSeparator()
                + "Captured Console Logs" + System.lineSeparator()
                + "clientSecret=plain-secret" + System.lineSeparator()
                + "DataAggregation-Thread-writer-request-fallback wrote Alice";

        support.recordAccessLog(null,
                null,
                "No token invocation",
                "request-fallback",
                "POST",
                LocalDateTime.of(2026, 7, 1, 10, 0),
                System.nanoTime(),
                true,
                200,
                null,
                null,
                summary,
                fallback,
                "127.0.0.1",
                "JUnit",
                1L,
                1L,
                0L,
                OpenServiceInvocationLogService.ArchiveResult.skipped(RunLogStorageService.STORAGE_LOCAL, fallback.length()));

        DataIngestionAccessLogEntity entity = insertedEntity(accessLogMapper);
        assertTrue(entity.getSystemLog().contains("Request Headers"));
        assertTrue(entity.getSystemLog().contains("Captured Console Logs"));
        assertTrue(entity.getSystemLog().contains("DataAggregation-Thread-writer-request-fallback"));
        assertTrue(entity.getSystemLog().contains("Alice"));
        assertTrue(entity.getSystemLog().contains("\"X-Data-Ingestion-Token\":\"******\""));
        assertTrue(entity.getSystemLog().contains("clientSecret=******"));
        assertFalse(entity.getSystemLog().contains("plain-token"));
        assertFalse(entity.getSystemLog().contains("plain-secret"));
        assertEquals(OpenServiceInvocationLogService.ARCHIVE_SKIPPED, entity.getLogArchiveStatus());
    }

    @Test
    void shouldKeepSummarySystemLogWhenObjectArchiveIsAvailable() {
        DataIngestionAccessLogMapper accessLogMapper = mock(DataIngestionAccessLogMapper.class);
        DataIngestionAccessLogSupport support = new DataIngestionAccessLogSupport(accessLogMapper,
                mock(DataIngestionAccessCounterMapper.class));
        String summary = "Invocation Summary" + System.lineSeparator()
                + "[requestId] request-object";
        String fallback = summary + System.lineSeparator()
                + "Captured Console Logs" + System.lineSeparator()
                + "DataAggregation-Thread-writer-request-object wrote Alice";

        support.recordAccessLog(null,
                null,
                "No token invocation",
                "request-object",
                "POST",
                LocalDateTime.of(2026, 7, 1, 10, 1),
                System.nanoTime(),
                true,
                200,
                null,
                null,
                summary,
                fallback,
                "127.0.0.1",
                "JUnit",
                1L,
                1L,
                0L,
                OpenServiceInvocationLogService.ArchiveResult.objectStorage(fallback.length(), "studio-log-bucket", "studio/invocation.log"));

        DataIngestionAccessLogEntity entity = insertedEntity(accessLogMapper);
        assertEquals(summary, entity.getSystemLog());
        assertFalse(entity.getSystemLog().contains("Captured Console Logs"));
        assertEquals(OpenServiceInvocationLogService.ARCHIVE_AVAILABLE, entity.getLogArchiveStatus());
    }

    private DataIngestionAccessLogEntity insertedEntity(DataIngestionAccessLogMapper accessLogMapper) {
        ArgumentCaptor<DataIngestionAccessLogEntity> captor = ArgumentCaptor.forClass(DataIngestionAccessLogEntity.class);
        verify(accessLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    private static void stubObjectDownload(RunLogObjectStore objectStore,
                                           String bucket,
                                           String objectKey,
                                           String content) {
        doAnswer(invocation -> {
            Path target = invocation.getArgument(2);
            Files.write(target, content.getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(objectStore).downloadTo(eq(bucket), eq(objectKey), any(Path.class));
    }

    private static OpenServiceInvocationLogService invocationLogService(DataIngestionAccessLogMapper ingestionMapper) {
        return invocationLogService(ingestionMapper, mock(RunLogObjectStore.class));
    }

    private static OpenServiceInvocationLogService invocationLogService(DataIngestionAccessLogMapper ingestionMapper,
                                                                        RunLogObjectStore objectStore) {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        return new OpenServiceInvocationLogService(
                new StudioPlatformProperties(),
                new RunLogStorageService(new StudioPlatformProperties(), objectStore, mock(CloudObjectStorageService.class)),
                mock(DataServiceAccessLogMapper.class),
                ingestionMapper,
                mock(ProtocolConversionAccessLogMapper.class),
                securityService,
                accessService,
                new ObjectMapper());
    }

    private static OpenServiceInvocationLogService objectArchiveInvocationLogService(DataIngestionAccessLogMapper ingestionMapper,
                                                                                    RunLogObjectStore objectStore,
                                                                                    CloudObjectStorageService cloudObjectStorageService) {
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService accessService = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(accessService.currentProjectId()).thenReturn(100L);
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.getInvocationLog().setStorageType(RunLogStorageService.STORAGE_OBJECT);
        return new OpenServiceInvocationLogService(
                properties,
                new RunLogStorageService(properties, objectStore, cloudObjectStorageService),
                mock(DataServiceAccessLogMapper.class),
                ingestionMapper,
                mock(ProtocolConversionAccessLogMapper.class),
                securityService,
                accessService,
                new ObjectMapper());
    }

    private static DataIngestionAccessLogEntity accessLogPointer() {
        DataIngestionAccessLogEntity entity = new DataIngestionAccessLogEntity();
        entity.setId(77L);
        entity.setTenantId("default");
        entity.setProjectId(100L);
        entity.setUpdatedAt(LocalDateTime.of(2026, 7, 2, 10, 0));
        entity.setRequestId("request-sectioned");
        entity.setLogStorageType(RunLogStorageService.STORAGE_LOCAL);
        entity.setLogArchiveStatus(OpenServiceInvocationLogService.ARCHIVE_SKIPPED);
        return entity;
    }

    private static DataIngestionAccessLogEntity accessLogObjectPointer() {
        DataIngestionAccessLogEntity entity = accessLogPointer();
        entity.setId(78L);
        entity.setRequestId("request-sectioned-object");
        entity.setLogStorageType(RunLogStorageService.STORAGE_OBJECT);
        entity.setLogObjectBucket("studio-log-bucket");
        entity.setLogObjectKey("studio/invocation/sectioned.log");
        entity.setLogCharset(StandardCharsets.UTF_8.name());
        entity.setLogArchiveStatus(OpenServiceInvocationLogService.ARCHIVE_AVAILABLE);
        return entity;
    }

    private static DataIngestionAccessLogEntity accessLogFallback(String content) {
        DataIngestionAccessLogEntity entity = new DataIngestionAccessLogEntity();
        entity.setUpdatedAt(LocalDateTime.of(2026, 7, 2, 10, 1));
        entity.setSystemLog(content);
        return entity;
    }

    private static String sectionedLog() {
        return "Invocation Summary" + System.lineSeparator()
                + "[requestId] request-sectioned" + System.lineSeparator()
                + targetSection("target_1_orders_2026070201", "orders", "orders target wrote Alice")
                + targetSection("target_2_customers_2026070202", "customers", "customers target wrote Bob");
    }

    private static String objectIndexedLog(boolean includeFailedInlineFallback) {
        StringBuilder builder = new StringBuilder();
        builder.append("Invocation Summary").append(System.lineSeparator())
                .append("[requestId] request-sectioned-object").append(System.lineSeparator())
                .append(System.lineSeparator())
                .append(OpenServiceInvocationLogService.DATA_INGESTION_TARGET_LOG_OBJECT_INDEX_TITLE).append(System.lineSeparator())
                .append("--------------------------------------").append(System.lineSeparator())
                .append(targetObjectIndex("target_1_orders_2026070201", "orders",
                        "studio/invocation/sectioned/targets/target_1_orders_2026070201.log",
                        OpenServiceInvocationLogService.ARCHIVE_AVAILABLE,
                        null))
                .append(System.lineSeparator())
                .append(targetObjectIndex("target_2_customers_2026070202", "customers",
                        includeFailedInlineFallback ? null : "studio/invocation/sectioned/targets/target_2_customers_2026070202.log",
                        includeFailedInlineFallback ? OpenServiceInvocationLogService.ARCHIVE_FAILED : OpenServiceInvocationLogService.ARCHIVE_AVAILABLE,
                        includeFailedInlineFallback ? "simulated upload failure" : null))
                .append(System.lineSeparator());
        if (includeFailedInlineFallback) {
            builder.append(targetSection("target_2_customers_2026070202", "customers", "customers target wrote Bob"));
        }
        builder.append("Captured Console Logs").append(System.lineSeparator())
                .append("---------------------").append(System.lineSeparator())
                .append("main invocation log").append(System.lineSeparator());
        return builder.toString();
    }

    private static String targetObjectIndex(String sectionKey,
                                            String sourceCode,
                                            String objectKey,
                                            String archiveStatus,
                                            String archiveError) {
        return "[sectionKey] " + sectionKey + System.lineSeparator()
                + "[sourceCode] " + sourceCode + System.lineSeparator()
                + "[sourceName] " + sourceCode + System.lineSeparator()
                + "[targetDatasourceName] ds_" + sourceCode + System.lineSeparator()
                + "[targetModelName] model_" + sourceCode + System.lineSeparator()
                + "[receivedCount] 1" + System.lineSeparator()
                + "[successCount] " + (OpenServiceInvocationLogService.ARCHIVE_FAILED.equals(archiveStatus) ? "0" : "1") + System.lineSeparator()
                + "[failedCount] " + (OpenServiceInvocationLogService.ARCHIVE_FAILED.equals(archiveStatus) ? "1" : "0") + System.lineSeparator()
                + "[status] " + (OpenServiceInvocationLogService.ARCHIVE_FAILED.equals(archiveStatus) ? "FAILED" : "SUCCESS") + System.lineSeparator()
                + "[message] -" + System.lineSeparator()
                + "[jobId] " + ("orders".equals(sourceCode) ? "2026070201" : "2026070202") + System.lineSeparator()
                + "[logObjectKey] " + (objectKey == null ? "-" : objectKey) + System.lineSeparator()
                + "[logSizeBytes] 256" + System.lineSeparator()
                + "[archiveStatus] " + archiveStatus + System.lineSeparator()
                + "[archiveError] " + (archiveError == null ? "-" : archiveError) + System.lineSeparator();
    }

    private static String targetObjectLog(String sectionKey, String sourceCode, String logLine) {
        return "Target Summary" + System.lineSeparator()
                + "--------------" + System.lineSeparator()
                + "[sectionKey] " + sectionKey + System.lineSeparator()
                + "[sourceCode] " + sourceCode + System.lineSeparator()
                + "[sourceName] " + sourceCode + System.lineSeparator()
                + "[targetDatasourceName] ds_" + sourceCode + System.lineSeparator()
                + "[targetModelName] model_" + sourceCode + System.lineSeparator()
                + "[receivedCount] 1" + System.lineSeparator()
                + "[successCount] 1" + System.lineSeparator()
                + "[failedCount] 0" + System.lineSeparator()
                + "[status] SUCCESS" + System.lineSeparator()
                + "[message] -" + System.lineSeparator()
                + "[jobId] " + ("orders".equals(sourceCode) ? "2026070201" : "2026070202") + System.lineSeparator()
                + System.lineSeparator()
                + "Target Captured Console Logs" + System.lineSeparator()
                + "----------------------------" + System.lineSeparator()
                + logLine + System.lineSeparator();
    }

    private static String targetSection(String sectionKey, String sourceCode, String logLine) {
        return OpenServiceInvocationLogService.DATA_INGESTION_TARGET_LOG_START_PREFIX + sectionKey + " =====" + System.lineSeparator()
                + "Target Summary" + System.lineSeparator()
                + "--------------" + System.lineSeparator()
                + "[sectionKey] " + sectionKey + System.lineSeparator()
                + "[sourceCode] " + sourceCode + System.lineSeparator()
                + "[sourceName] " + sourceCode + System.lineSeparator()
                + "[targetDatasourceName] ds_" + sourceCode + System.lineSeparator()
                + "[targetModelName] model_" + sourceCode + System.lineSeparator()
                + "[receivedCount] 1" + System.lineSeparator()
                + "[successCount] 1" + System.lineSeparator()
                + "[failedCount] 0" + System.lineSeparator()
                + "[status] SUCCESS" + System.lineSeparator()
                + "[message] -" + System.lineSeparator()
                + "[jobId] " + ("orders".equals(sourceCode) ? "2026070201" : "2026070202") + System.lineSeparator()
                + System.lineSeparator()
                + "Target Captured Console Logs" + System.lineSeparator()
                + "----------------------------" + System.lineSeparator()
                + logLine + System.lineSeparator()
                + OpenServiceInvocationLogService.DATA_INGESTION_TARGET_LOG_END_PREFIX + sectionKey + " =====" + System.lineSeparator();
    }

    private static DataIngestionSourceBinding binding(String sourceCode, String sourcePath) {
        DataIngestionSourceBinding binding = new DataIngestionSourceBinding();
        binding.setSourceCode(sourceCode);
        binding.setSourceName(sourceCode);
        binding.setSourcePosition(DataIngestionSourcePosition.BODY);
        binding.setSourcePath(sourcePath);
        binding.setDatasourceId(Long.valueOf(1L));
        binding.setDatasourceName("ds_" + sourceCode);
        binding.setModelId(Long.valueOf(1L));
        binding.setModelName("model_" + sourceCode);
        binding.setFieldMappings(Arrays.asList(mapping("name")));
        binding.setWriterOptions(new LinkedHashMap<String, Object>());
        binding.setEnabled(Boolean.TRUE);
        return binding;
    }

    private static DataIngestionFieldMapping mapping(String field) {
        DataIngestionFieldMapping mapping = new DataIngestionFieldMapping();
        mapping.setSourcePosition(DataIngestionSourcePosition.BODY);
        mapping.setSourceField(field);
        mapping.setTargetField(field);
        mapping.setValueType(FieldValueType.STRING);
        mapping.setRequired(Boolean.TRUE);
        return mapping;
    }

    private static Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int index = 0; index + 1 < entries.length; index += 2) {
            map.put(String.valueOf(entries[index]), entries[index + 1]);
        }
        return map;
    }

    private static final class ConcurrentFailingAssembler extends CollectionTaskAssemblerService {
        private final CountDownLatch entered;
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();

        private ConcurrentFailingAssembler(int expectedConcurrentCalls) {
            super(null, null, null, null);
            this.entered = new CountDownLatch(expectedConcurrentCalls);
        }

        @Override
        public Map<String, Object> assembleWriter(Long datasourceId,
                                                  Long modelId,
                                                  List<String> targetFields,
                                                  Map<String, Object> writerOptions) {
            int currentActive = active.incrementAndGet();
            updateMaxActive(currentActive);
            try {
                entered.countDown();
                entered.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted");
            } finally {
                active.decrementAndGet();
            }
            throw new RuntimeException("simulated writer assembly failure");
        }

        private void updateMaxActive(int currentActive) {
            while (true) {
                int currentMax = maxActive.get();
                if (currentActive <= currentMax || maxActive.compareAndSet(currentMax, currentActive)) {
                    return;
                }
            }
        }

        private int maxActive() {
            return maxActive.get();
        }
    }
}
