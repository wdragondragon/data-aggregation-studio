package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.dto.model.FileTransferQueueEventView;
import com.jdragon.studio.dto.model.FileTransferRunItemView;
import com.jdragon.studio.dto.model.FileTransferRunView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.FileTransferEventConsumerCursorEntity;
import com.jdragon.studio.infra.entity.FileTransferEventOutboxEntity;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.mapper.FileTransferEventConsumerCursorMapper;
import com.jdragon.studio.infra.mapper.FileTransferEventOutboxMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import com.jdragon.studio.infra.model.FileTransferEventMode;
import com.jdragon.studio.infra.model.FileTransferOutboxEventType;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileTransferEventServiceOutboxTest {

    @BeforeAll
    static void initializeLambdaMetadata() {
        initialize(FileTransferEventOutboxEntity.class);
        initialize(FileTransferEventConsumerCursorEntity.class);
        initialize(FileTransferRunEntity.class);
        initialize(FileTransferRunItemEntity.class);
    }

    @Test
    void twoServerInstancesConsumeTheSameCommittedEventWithIndependentCursors() {
        Fixture fixture = new Fixture();
        CapturingEmitter serverA = new CapturingEmitter();
        CapturingEmitter serverB = new CapturingEmitter();
        FileTransferEventService serviceA = fixture.service("server-a", serverA);
        FileTransferEventService serviceB = fixture.service("server-b", serverB);
        serviceA.connect();
        serviceB.connect();
        serverA.clear();
        serverB.clear();

        fixture.runs.add(run(10L, 100L));
        fixture.events.add(event(1L, 10L, 100L, null, FileTransferOutboxEventType.RUN_CHANGED));
        serviceA.publishDatabaseChanges();
        serviceB.publishDatabaseChanges();

        assertThat(serverA.businessEvents()).extracting(CapturedEvent::id).containsExactly("1");
        assertThat(serverB.businessEvents()).extracting(CapturedEvent::id).containsExactly("1");
        assertThat(fixture.cursors.get(new CursorKey("server-a", "tenant-a", 10L)).getLastEventId())
                .isEqualTo(1L);
        assertThat(fixture.cursors.get(new CursorKey("server-b", "tenant-a", 10L)).getLastEventId())
                .isEqualTo(1L);
    }

    @Test
    void reconnectReplaysFromLastSuccessfulEventAndExpiredCursorRequiresSnapshot() {
        Fixture fixture = new Fixture();
        fixture.runs.add(run(10L, 100L));
        fixture.events.add(event(100L, 10L, 100L, null, FileTransferOutboxEventType.RUN_CHANGED));
        fixture.events.add(event(101L, 10L, 100L, null, FileTransferOutboxEventType.RUN_CHANGED));

        CapturingEmitter replay = new CapturingEmitter();
        fixture.service("server-a", replay).connect("99");
        assertThat(replay.businessEvents()).hasSize(1);
        assertThat(replay.businessEvents().get(0).id()).isEqualTo("101");
        assertThat(replay.businessEvents().get(0).view().getEventId()).isEqualTo(101L);

        CapturingEmitter expired = new CapturingEmitter();
        fixture.service("server-b", expired).connect("98");
        assertThat(expired.events).hasSize(1);
        assertThat(expired.events.get(0).name()).isEqualTo("snapshot");
        assertThat(expired.events.get(0).id()).isEqualTo("101");
        assertThat(expired.events.get(0).view().getType()).isEqualTo("SNAPSHOT_REQUIRED");
    }

    @Test
    void failedSendDoesNotPreventReplayFromTheBrowserCursor() {
        Fixture fixture = new Fixture();
        CapturingEmitter failedConnection = new CapturingEmitter();
        CapturingEmitter reconnected = new CapturingEmitter();
        FileTransferEventService service = fixture.service("server-a", failedConnection, reconnected);
        service.connect();
        failedConnection.clear();
        failedConnection.failBusinessEvents = true;

        fixture.runs.add(run(10L, 100L));
        fixture.events.add(event(1L, 10L, 100L, null, FileTransferOutboxEventType.RUN_CHANGED));
        service.publishDatabaseChanges();
        assertThat(failedConnection.businessEvents()).isEmpty();

        service.connect("0");
        assertThat(reconnected.businessEvents()).extracting(CapturedEvent::id).containsExactly("1");
    }

    @Test
    void oneFailedBrowserConnectionDoesNotBlockAnotherConnectionOnTheSameServer() {
        Fixture fixture = new Fixture();
        CapturingEmitter failedConnection = new CapturingEmitter();
        CapturingEmitter healthyConnection = new CapturingEmitter();
        FileTransferEventService service = fixture.service("server-a", failedConnection, healthyConnection);
        service.connect();
        service.connect();
        failedConnection.clear();
        healthyConnection.clear();
        failedConnection.failBusinessEvents = true;

        fixture.runs.add(run(10L, 100L));
        fixture.events.add(event(1L, 10L, 100L, null, FileTransferOutboxEventType.RUN_CHANGED));
        service.publishDatabaseChanges();

        assertThat(failedConnection.businessEvents()).isEmpty();
        assertThat(healthyConnection.businessEvents()).extracting(CapturedEvent::id).containsExactly("1");
        assertThat(fixture.cursors.get(new CursorKey("server-a", "tenant-a", 10L)).getLastEventId())
                .isEqualTo(1L);
    }

    @Test
    void interleavedRunAndRemovalEventsArePublishedInAscendingAggregateEventOrder() {
        Fixture fixture = new Fixture();
        fixture.runs.add(run(10L, 100L));
        fixture.runs.add(run(10L, 200L));
        fixture.items.add(item(10L, 100L, 501L));
        fixture.events.add(event(100L, 10L, 100L, 500L, FileTransferOutboxEventType.ITEM_REMOVED));
        fixture.events.add(event(101L, 10L, 200L, null, FileTransferOutboxEventType.RUN_CHANGED));
        fixture.events.add(event(102L, 10L, 100L, 501L, FileTransferOutboxEventType.ITEM_CHANGED));
        fixture.events.add(event(103L, 10L, 300L, null, FileTransferOutboxEventType.RUN_REMOVED));
        CapturingEmitter emitter = new CapturingEmitter();

        fixture.service("server-a", emitter).connect("99");

        assertThat(emitter.businessEvents()).extracting(CapturedEvent::id)
                .containsExactly("100", "101", "102", "103");
        assertThat(emitter.businessEvents()).extracting(event -> event.view().getType())
                .containsExactly("ITEM_REMOVED", "RUN_CHANGED", "RUN_CHANGED", "RUN_REMOVED");
        assertThat(emitter.businessEvents()).extracting(event -> event.view().getRunId())
                .containsExactly(100L, 200L, 100L, 300L);
    }

    @Test
    void invalidOrExcessiveReplayRequestRequiresSnapshot() {
        Fixture fixture = new Fixture();
        fixture.runs.add(run(10L, 100L));
        fixture.events.add(event(100L, 10L, 100L, null, FileTransferOutboxEventType.RUN_CHANGED));
        fixture.events.add(event(101L, 10L, 100L, null, FileTransferOutboxEventType.RUN_CHANGED));

        CapturingEmitter invalid = new CapturingEmitter();
        fixture.service("server-a", 5_000, invalid).connect("not-a-number");
        assertThat(invalid.events).hasSize(1);
        assertThat(invalid.events.get(0).view().getType()).isEqualTo("SNAPSHOT_REQUIRED");
        assertThat(invalid.events.get(0).id()).isEqualTo("101");

        CapturingEmitter excessive = new CapturingEmitter();
        fixture.service("server-b", 1, excessive).connect("99");
        assertThat(excessive.events).hasSize(1);
        assertThat(excessive.events.get(0).view().getType()).isEqualTo("SNAPSHOT_REQUIRED");
        assertThat(excessive.events.get(0).id()).isEqualTo("101");
    }

    @Test
    void outboxEventsAndViewsAreIsolatedByTenantAndProject() {
        Fixture fixture = new Fixture();
        fixture.runs.add(run("tenant-a", 10L, 100L));
        fixture.runs.add(run("tenant-a", 20L, 200L));
        fixture.runs.add(run("tenant-b", 10L, 300L));
        fixture.events.add(event("tenant-a", 100L, 10L, 100L, null,
                FileTransferOutboxEventType.RUN_CHANGED));
        fixture.events.add(event("tenant-a", 101L, 20L, 200L, null,
                FileTransferOutboxEventType.RUN_CHANGED));
        fixture.events.add(event("tenant-b", 102L, 10L, 300L, null,
                FileTransferOutboxEventType.RUN_CHANGED));
        CapturingEmitter emitter = new CapturingEmitter();

        fixture.service("server-a", emitter).connect("99");

        assertThat(emitter.businessEvents()).hasSize(1);
        assertThat(emitter.businessEvents().get(0).id()).isEqualTo("100");
        assertThat(emitter.businessEvents().get(0).view().getRunId()).isEqualTo(100L);
    }

    @Test
    void removedEntitiesStillProduceDeleteEvents() {
        Fixture fixture = new Fixture();
        fixture.runs.add(run(10L, 100L));
        fixture.events.add(event(100L, 10L, 100L, 501L, FileTransferOutboxEventType.ITEM_REMOVED));
        fixture.events.add(event(101L, 10L, 200L, null, FileTransferOutboxEventType.RUN_REMOVED));
        CapturingEmitter emitter = new CapturingEmitter();

        fixture.service("server-a", emitter).connect("99");

        assertThat(emitter.businessEvents()).extracting(event -> event.view().getType())
                .containsExactly("ITEM_REMOVED", "RUN_REMOVED");
        assertThat(emitter.businessEvents().get(0).view().getItemId()).isEqualTo(501L);
        assertThat(emitter.businessEvents().get(1).view().getRunId()).isEqualTo(200L);
    }

    @Test
    void changedItemsAreMergedAndSplitAtTwoHundredItems() {
        Fixture fixture = new Fixture();
        fixture.runs.add(run(10L, 100L));
        for (int index = 0; index < 201; index++) {
            long itemId = 1_000L + index;
            fixture.items.add(item(10L, 100L, itemId));
            fixture.events.add(event(100L + index, 10L, 100L, itemId,
                    FileTransferOutboxEventType.ITEM_CHANGED));
        }
        CapturingEmitter emitter = new CapturingEmitter();

        fixture.service("server-a", emitter).connect("99");

        assertThat(emitter.businessEvents()).hasSize(2);
        assertThat(emitter.businessEvents().get(0).view().getItems()).hasSize(200);
        assertThat(emitter.businessEvents().get(1).view().getItems()).hasSize(1);
        assertThat(emitter.businessEvents()).extracting(CapturedEvent::id).containsExactly("299", "300");
    }

    @Test
    void livePayloadOverlaysOnlyAnActiveItemWithoutChangingConfirmedBytes() {
        Fixture fixture = new Fixture();
        fixture.runs.add(run(10L, 100L));
        FileTransferRunItemEntity item = item(10L, 100L, 501L);
        item.setTransferredBytes(0L);
        fixture.items.add(item);
        FileTransferEventOutboxEntity event = event(100L, 10L, 100L, 501L,
                FileTransferOutboxEventType.ITEM_CHANGED);
        event.setPayloadJson(Map.of("live", true, "confirmedBytes", 0L,
                "observedBytes", 4_096L, "liveBytesPerSecond", 2_048L));
        fixture.events.add(event);
        CapturingEmitter emitter = new CapturingEmitter();

        fixture.service("server-a", emitter).connect("99");

        FileTransferRunItemView view = emitter.businessEvents().get(0).view().getItems().get(0);
        assertThat(view.getTransferredBytes()).isZero();
        assertThat(view.getObservedBytes()).isEqualTo(4_096L);
        assertThat(view.getCurrentBytesPerSecond()).isEqualTo(2_048L);
        assertThat(view.getLive()).isTrue();
    }

    @Test
    void checksumRebuildPayloadExposesValidationProgressSeparately() {
        Fixture fixture = new Fixture();
        fixture.runs.add(run(10L, 100L));
        FileTransferRunItemEntity item = item(10L, 100L, 501L);
        item.setTransferredBytes(3_072L);
        fixture.items.add(item);
        FileTransferEventOutboxEntity event = event(100L, 10L, 100L, 501L,
                FileTransferOutboxEventType.ITEM_CHANGED);
        event.setPayloadJson(Map.of("live", true, "confirmedBytes", 3_072L,
                "observedBytes", 3_072L, "activityBytes", 4_096L,
                "liveBytesPerSecond", 512L, "resumePhase", "REBUILDING_CHECKSUM"));
        fixture.events.add(event);
        CapturingEmitter emitter = new CapturingEmitter();

        fixture.service("server-a", emitter).connect("99");

        FileTransferRunItemView view = emitter.businessEvents().get(0).view().getItems().get(0);
        assertThat(view.getTransferredBytes()).isEqualTo(3_072L);
        assertThat(view.getObservedBytes()).isEqualTo(3_072L);
        assertThat(view.getResumePhase()).isEqualTo("REBUILDING_CHECKSUM");
        assertThat(view.getResumeCheckedBytes()).isEqualTo(1_024L);
        assertThat(view.getResumeTotalBytes()).isEqualTo(3_072L);
        assertThat(view.getCurrentBytesPerSecond()).isEqualTo(512L);
    }

    @Test
    void targetChecksumPayloadOverlaysOnlyTheVerificationProgress() {
        Fixture fixture = new Fixture();
        fixture.runs.add(run(10L, 100L));
        FileTransferRunItemEntity item = item(10L, 100L, 501L);
        item.setStatus("VERIFYING");
        item.setFileSize(8_192L);
        item.setTransferredBytes(8_192L);
        fixture.items.add(item);
        FileTransferEventOutboxEntity event = event(100L, 10L, 100L, 501L,
                FileTransferOutboxEventType.ITEM_CHANGED);
        event.setPayloadJson(Map.of("live", true, "confirmedBytes", 8_192L,
                "observedBytes", 8_192L, "activityBytes", 12_288L,
                "liveBytesPerSecond", 512L, "verificationPhase", "TARGET_CHECKSUM",
                "verificationBytes", 4_096L, "verificationTotalBytes", 8_192L));
        fixture.events.add(event);
        CapturingEmitter emitter = new CapturingEmitter();

        fixture.service("server-a", emitter).connect("99");

        FileTransferRunItemView view = emitter.businessEvents().get(0).view().getItems().get(0);
        assertThat(view.getTransferredBytes()).isEqualTo(8_192L);
        assertThat(view.getVerificationPhase()).isEqualTo("TARGET_CHECKSUM");
        assertThat(view.getVerificationBytes()).isEqualTo(4_096L);
        assertThat(view.getVerificationTotalBytes()).isEqualTo(8_192L);
        assertThat(view.getCurrentBytesPerSecond()).isEqualTo(512L);
        assertThat(view.getLive()).isTrue();
    }

    @Test
    void staleLivePayloadDoesNotOverlayATerminalItem() {
        Fixture fixture = new Fixture();
        fixture.runs.add(run(10L, 100L));
        FileTransferRunItemEntity item = item(10L, 100L, 501L);
        item.setStatus("SUCCESS");
        item.setTransferredBytes(8_192L);
        fixture.items.add(item);
        FileTransferEventOutboxEntity event = event(100L, 10L, 100L, 501L,
                FileTransferOutboxEventType.ITEM_CHANGED);
        event.setPayloadJson(Map.of("live", true, "confirmedBytes", 0L,
                "observedBytes", 4_096L, "liveBytesPerSecond", 2_048L));
        fixture.events.add(event);
        CapturingEmitter emitter = new CapturingEmitter();

        fixture.service("server-a", emitter).connect("99");

        FileTransferRunItemView view = emitter.businessEvents().get(0).view().getItems().get(0);
        assertThat(view.getTransferredBytes()).isEqualTo(8_192L);
        assertThat(view.getObservedBytes()).isNull();
        assertThat(view.getLive()).isNull();
    }

    @Test
    void outboxModeDoesNotScanRunOrItemTablesWhenNoEventsExist() {
        Fixture fixture = new Fixture();
        CapturingEmitter emitter = new CapturingEmitter();
        FileTransferEventService service = fixture.service("server-a", emitter);
        service.connect();
        clearInvocations(fixture.runMapper, fixture.itemMapper);

        service.publishDatabaseChanges();

        verify(fixture.runMapper, never()).selectList(any(LambdaQueryWrapper.class));
        verify(fixture.itemMapper, never()).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    void idlePollUsesCachedCursorAndDoesNotWriteOrCountLag() {
        Fixture fixture = new Fixture();
        CapturingEmitter emitter = new CapturingEmitter();
        FileTransferEventService service = fixture.service("server-a", emitter);
        service.connect();
        clearInvocations(fixture.cursorMapper, fixture.outboxMapper);

        service.publishDatabaseChanges();

        verify(fixture.cursorMapper, never()).selectOne(any(LambdaQueryWrapper.class));
        verify(fixture.cursorMapper, never()).update(any(), any());
        verify(fixture.outboxMapper, never()).selectOne(any(LambdaQueryWrapper.class));
        verify(fixture.outboxMapper, never()).selectCount(any(LambdaQueryWrapper.class));
        verify(fixture.outboxMapper).selectList(any(LambdaQueryWrapper.class));
        assertThat(service.status().cursorLag()).isZero();
    }

    @Test
    void cursorLagCountsRemainingRowsInsteadOfSubtractingSnowflakeIds() {
        Fixture fixture = new Fixture();
        CapturingEmitter emitter = new CapturingEmitter();
        FileTransferEventService service = fixture.service("server-a", emitter);
        service.connect();
        emitter.clear();
        fixture.runs.add(run(10L, 100L));
        long firstId = 2_085_898_514_234_388_481L;
        for (int index = 0; index < 501; index++) {
            fixture.events.add(event(firstId + index, 10L, 100L, null,
                    FileTransferOutboxEventType.RUN_CHANGED));
        }

        service.publishDatabaseChanges();

        assertThat(service.status().cursorLag()).isEqualTo(1L);
    }

    @Test
    void heartbeatRefreshesInstanceLagWithoutBrowserConnections() {
        Fixture fixture = new Fixture();
        FileTransferEventConsumerCursorEntity cursor = new FileTransferEventConsumerCursorEntity();
        cursor.setId(1L);
        cursor.setInstanceId("server-a");
        cursor.setTenantId("tenant-a");
        cursor.setProjectId(10L);
        cursor.setLastEventId(100L);
        fixture.cursors.put(new CursorKey("server-a", "tenant-a", 10L), cursor);
        fixture.events.add(event(101L, 10L, 100L, null, FileTransferOutboxEventType.RUN_CHANGED));
        FileTransferEventService service = fixture.service("server-a");

        service.heartbeat();

        assertThat(service.status().cursorLag()).isEqualTo(1L);
    }

    private static final class Fixture {
        private final FileTransferEventOutboxMapper outboxMapper = mock(FileTransferEventOutboxMapper.class);
        private final FileTransferEventConsumerCursorMapper cursorMapper =
                mock(FileTransferEventConsumerCursorMapper.class);
        private final FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        private final FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        private final FileTransferRunService runService = mock(FileTransferRunService.class);
        private final StudioSecurityService securityService = mock(StudioSecurityService.class);
        private final ProjectResourceAccessService projectAccess = mock(ProjectResourceAccessService.class);
        private final AtomicReference<String> tenant = new AtomicReference<>("tenant-a");
        private final AtomicLong project = new AtomicLong(10L);
        private final AtomicLong cursorIds = new AtomicLong(10_000L);
        private final List<FileTransferEventOutboxEntity> events = new ArrayList<>();
        private final List<FileTransferRunEntity> runs = new ArrayList<>();
        private final List<FileTransferRunItemEntity> items = new ArrayList<>();
        private final Map<CursorKey, FileTransferEventConsumerCursorEntity> cursors = new HashMap<>();

        @SuppressWarnings("unchecked")
        private Fixture() {
            when(securityService.currentTenantId()).thenAnswer(ignored -> tenant.get());
            when(projectAccess.requireCurrentProjectId()).thenAnswer(ignored -> project.get());
            when(outboxMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
                LambdaQueryWrapper<FileTransferEventOutboxEntity> query = invocation.getArgument(0);
                List<FileTransferEventOutboxEntity> scoped = scopedEvents(query);
                if (scoped.isEmpty()) {
                    return null;
                }
                boolean descending = query.getSqlSegment().toLowerCase().contains("desc");
                return descending ? scoped.get(scoped.size() - 1) : scoped.get(0);
            });
            when(outboxMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
                LambdaQueryWrapper<FileTransferEventOutboxEntity> query = invocation.getArgument(0);
                List<Long> parameters = new ArrayList<>(numericParameters(query));
                parameters.remove(project.get());
                long after = parameters.isEmpty() ? 0L : parameters.get(0);
                int limit = limit(query.getSqlSegment());
                return scopedEvents(query).stream().filter(value -> value.getId() > after).limit(limit).toList();
            });
            when(outboxMapper.selectCount(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
                LambdaQueryWrapper<FileTransferEventOutboxEntity> query = invocation.getArgument(0);
                List<Long> parameters = new ArrayList<>(numericParameters(query));
                parameters.remove(project.get());
                long after = parameters.isEmpty() ? 0L : parameters.get(0);
                return scopedEvents(query).stream().filter(value -> value.getId() > after).count();
            });
            when(cursorMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
                LambdaQueryWrapper<FileTransferEventConsumerCursorEntity> query = invocation.getArgument(0);
                List<String> strings = stringParameters(query);
                List<Long> numbers = numericParameters(query);
                String instanceId = strings.stream().filter(value -> value.startsWith("server-")).findFirst()
                        .orElseThrow();
                String tenantId = strings.stream().filter(value -> value.startsWith("tenant-")).findFirst()
                        .orElseThrow();
                return cursors.get(new CursorKey(instanceId, tenantId, numbers.get(0)));
            });
            when(cursorMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
                LambdaQueryWrapper<FileTransferEventConsumerCursorEntity> query = invocation.getArgument(0);
                List<String> strings = stringParameters(query);
                String instanceId = strings.stream().filter(value -> value.startsWith("server-"))
                        .findFirst().orElseThrow();
                return cursors.values().stream()
                        .filter(value -> instanceId.equals(value.getInstanceId()))
                        .toList();
            });
            when(cursorMapper.insert(any(FileTransferEventConsumerCursorEntity.class))).thenAnswer(invocation -> {
                FileTransferEventConsumerCursorEntity cursor = invocation.getArgument(0);
                cursor.setId(cursorIds.incrementAndGet());
                cursors.put(new CursorKey(cursor.getInstanceId(), cursor.getTenantId(), cursor.getProjectId()), cursor);
                return 1;
            });
            when(cursorMapper.update(any(), any())).thenReturn(1);
            when(runMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
                Scope scope = new Scope(tenant.get(), project.get());
                return runs.stream().filter(value -> scope.matches(value.getTenantId(), value.getProjectId())).toList();
            });
            when(itemMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
                Scope scope = new Scope(tenant.get(), project.get());
                return items.stream().filter(value -> scope.matches(value.getTenantId(), value.getProjectId())).toList();
            });
            when(runService.toRunViews(any())).thenAnswer(invocation -> {
                List<FileTransferRunEntity> source = invocation.getArgument(0);
                return source.stream().map(Fixture::runView).toList();
            });
            when(runService.toItemViews(any())).thenAnswer(invocation -> {
                List<FileTransferRunItemEntity> source = invocation.getArgument(0);
                return source.stream().map(Fixture::itemView).toList();
            });
        }

        private FileTransferEventService service(String instanceId, CapturingEmitter... emitters) {
            return service(instanceId, 5_000, emitters);
        }

        private FileTransferEventService service(String instanceId, int replayMaxEvents,
                                                 CapturingEmitter... emitters) {
            StudioPlatformProperties properties = new StudioPlatformProperties();
            properties.setInstanceId(instanceId);
            properties.getFileTransfer().setEventMode(FileTransferEventMode.OUTBOX);
            properties.getFileTransfer().setReplayMaxEvents(replayMaxEvents);
            Queue<CapturingEmitter> queue = new ArrayDeque<>(List.of(emitters));
            return new FileTransferEventService(runMapper, itemMapper, outboxMapper, cursorMapper, runService,
                    securityService, projectAccess, properties, new ClusterInstanceIdentity(properties),
                    new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class)) {
                @Override
                SseEmitter createEmitter() {
                    return queue.remove();
                }
            };
        }

        private List<FileTransferEventOutboxEntity> scopedEvents(LambdaQueryWrapper<?> query) {
            query.getSqlSegment();
            Scope scope = new Scope(tenant.get(), project.get());
            return events.stream()
                    .filter(value -> scope.matches(value.getTenantId(), value.getProjectId()))
                    .sorted(Comparator.comparingLong(FileTransferEventOutboxEntity::getId))
                    .toList();
        }

        private static List<String> stringParameters(LambdaQueryWrapper<?> query) {
            query.getSqlSegment();
            return query.getParamNameValuePairs().values().stream()
                    .filter(String.class::isInstance).map(String.class::cast).toList();
        }

        private static List<Long> numericParameters(LambdaQueryWrapper<?> query) {
            query.getSqlSegment();
            return query.getParamNameValuePairs().values().stream()
                    .filter(Number.class::isInstance).map(Number.class::cast).map(Number::longValue).toList();
        }

        private static int limit(String sql) {
            Matcher matcher = Pattern.compile("(?i)limit\\s+(\\d+)").matcher(sql);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
        }

        private static FileTransferRunView runView(FileTransferRunEntity entity) {
            FileTransferRunView view = new FileTransferRunView();
            view.setId(entity.getId());
            view.setProjectId(entity.getProjectId());
            view.setStatus(entity.getStatus());
            return view;
        }

        private static FileTransferRunItemView itemView(FileTransferRunItemEntity entity) {
            FileTransferRunItemView view = new FileTransferRunItemView();
            view.setId(entity.getId());
            view.setRunId(entity.getRunId());
            view.setStatus(entity.getStatus());
            view.setTransferredBytes(entity.getTransferredBytes());
            view.setCurrentBytesPerSecond(entity.getCurrentBytesPerSecond());
            return view;
        }
    }

    private static final class CapturingEmitter extends SseEmitter {
        private final List<CapturedEvent> events = new ArrayList<>();
        private boolean failBusinessEvents;

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            String id = null;
            String name = null;
            FileTransferQueueEventView view = null;
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
                Object data = part.getData();
                if (data instanceof String text) {
                    Matcher idMatcher = Pattern.compile("(?m)^id:([^\\r\\n]+)").matcher(text);
                    Matcher nameMatcher = Pattern.compile("(?m)^event:([^\\r\\n]+)").matcher(text);
                    if (idMatcher.find()) {
                        id = idMatcher.group(1).trim();
                    }
                    if (nameMatcher.find()) {
                        name = nameMatcher.group(1).trim();
                    }
                } else if (data instanceof FileTransferQueueEventView eventView) {
                    view = eventView;
                }
            }
            if (failBusinessEvents && view != null && !"SNAPSHOT_REQUIRED".equals(view.getType())) {
                throw new IOException("connection closed");
            }
            events.add(new CapturedEvent(id, name, view));
        }

        private List<CapturedEvent> businessEvents() {
            return events.stream().filter(value -> value.view() != null
                    && !"SNAPSHOT_REQUIRED".equals(value.view().getType())).toList();
        }

        private void clear() {
            events.clear();
        }
    }

    private static FileTransferEventOutboxEntity event(long id, long projectId, long runId, Long itemId,
                                                        FileTransferOutboxEventType type) {
        return event("tenant-a", id, projectId, runId, itemId, type);
    }

    private static FileTransferEventOutboxEntity event(String tenantId, long id, long projectId, long runId,
                                                        Long itemId, FileTransferOutboxEventType type) {
        FileTransferEventOutboxEntity entity = new FileTransferEventOutboxEntity();
        entity.setId(id);
        entity.setTenantId(tenantId);
        entity.setProjectId(projectId);
        entity.setEventType(type.name());
        entity.setRunId(runId);
        entity.setItemId(itemId);
        entity.setOccurredAt(LocalDateTime.of(2026, 8, 12, 12, 0).plusNanos(id));
        return entity;
    }

    private static FileTransferRunEntity run(long projectId, long runId) {
        return run("tenant-a", projectId, runId);
    }

    private static FileTransferRunEntity run(String tenantId, long projectId, long runId) {
        FileTransferRunEntity entity = new FileTransferRunEntity();
        entity.setId(runId);
        entity.setTenantId(tenantId);
        entity.setProjectId(projectId);
        entity.setStatus("RUNNING");
        return entity;
    }

    private static FileTransferRunItemEntity item(long projectId, long runId, long itemId) {
        FileTransferRunItemEntity entity = new FileTransferRunItemEntity();
        entity.setId(itemId);
        entity.setRunId(runId);
        entity.setTenantId("tenant-a");
        entity.setProjectId(projectId);
        entity.setStatus("TRANSFERRING");
        return entity;
    }

    private static void initialize(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "outbox-test"),
                    entityType);
        }
    }

    private record CapturedEvent(String id, String name, FileTransferQueueEventView view) {
    }

    private record CursorKey(String instanceId, String tenantId, Long projectId) {
    }

    private record Scope(String tenantId, Long projectId) {
        private boolean matches(String candidateTenantId, Long candidateProjectId) {
            return tenantId.equals(candidateTenantId) && projectId.equals(candidateProjectId);
        }
    }
}
