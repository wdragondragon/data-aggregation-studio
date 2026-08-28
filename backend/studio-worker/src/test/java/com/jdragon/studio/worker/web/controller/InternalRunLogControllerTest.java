package com.jdragon.studio.worker.web.controller;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.entity.RunLogChunkEntity;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.mapper.RunLogChunkMapper;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.worker.runtime.log.RunLogFileService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class InternalRunLogControllerTest {

    @Test
    void shouldReadOnlyTheLogOwnedByTheCurrentWorkerBoot() {
        Fixture fixture = fixture("instance-a", "boot-a", "C50");
        RunLogView expected = new RunLogView();
        expected.setRunRecordId(91L);
        when(fixture.runLogFileService.readPage(fixture.record, 1, 4096)).thenReturn(expected);

        assertThat(fixture.controller.viewLog(91L, 1, 4096).getData()).isSameAs(expected);
        verify(fixture.runLogFileService).readPage(fixture.record, 1, 4096);
    }

    @Test
    void shouldRejectARecordFromAnEarlierWorkerBoot() {
        Fixture fixture = fixture("instance-a", "boot-old", "C50");

        assertThatThrownBy(() -> fixture.controller.viewLog(91L, 1, 4096))
                .isInstanceOfSatisfying(StudioException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(StudioErrorCode.NOT_FOUND);
                    assertThat(exception.getMessage()).contains("worker boot");
                });
    }

    @Test
    void shouldRejectARecordFromAnotherRuntimeCluster() {
        Fixture fixture = fixture("instance-a", "boot-a", "C46");

        assertThatThrownBy(() -> fixture.controller.downloadLog(91L))
                .isInstanceOfSatisfying(StudioException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(StudioErrorCode.NOT_FOUND);
                    assertThat(exception.getMessage()).contains("runtime cluster");
                });
    }

    @Test
    void shouldReadOnlyTheChunkOwnedByTheCurrentWorker() {
        Fixture fixture = fixture("instance-a", "boot-a", "C50");
        RunLogChunkMapper chunkMapper = mock(RunLogChunkMapper.class);
        RunLogChunkEntity chunk = new RunLogChunkEntity();
        chunk.setId(88L);
        chunk.setRunRecordId(91L);
        chunk.setLocalPath("2026-07-23/stream-run-91.log");
        when(chunkMapper.selectOne(any())).thenReturn(chunk);
        RunLogView expected = new RunLogView();
        expected.setRunRecordId(91L);
        when(fixture.runLogFileService.readChunkPage(fixture.record, chunk, 2, 4096)).thenReturn(expected);

        InternalRunLogController controller = new InternalRunLogController(
                fixture.runRecordMapper, fixture.runLogFileService, fixture.properties,
                fixture.identity, chunkMapper);

        assertThat(controller.previewChunk(88L, 2, 4096).getData()).isSameAs(expected);
        verify(fixture.runLogFileService).readChunkPage(fixture.record, chunk, 2, 4096);
    }

    private Fixture fixture(String instanceId, String bootId, String actualClusterCode) {
        RunRecordMapper runRecordMapper = mock(RunRecordMapper.class);
        RunLogFileService runLogFileService = mock(RunLogFileService.class);
        StudioPlatformProperties properties = new StudioPlatformProperties();
        properties.setWorkerCode("worker-a");
        properties.setRuntimeClusterCode("C50");
        ClusterInstanceIdentity identity = mock(ClusterInstanceIdentity.class);
        when(identity.instanceId()).thenReturn("instance-a");
        when(identity.bootId()).thenReturn("boot-a");

        RunRecordEntity record = new RunRecordEntity();
        record.setId(91L);
        record.setWorkerCode("worker-a");
        record.setWorkerInstanceId(instanceId);
        record.setWorkerBootId(bootId);
        record.setActualClusterCode(actualClusterCode);
        record.setLogFilePath("2026-07-23/run-91.log");
        when(runRecordMapper.selectById(91L)).thenReturn(record);

        InternalRunLogController controller = new InternalRunLogController(
                runRecordMapper, runLogFileService, properties, identity);
        return new Fixture(controller, runLogFileService, record, runRecordMapper, properties, identity);
    }

    private static final class Fixture {
        private final InternalRunLogController controller;
        private final RunLogFileService runLogFileService;
        private final RunRecordEntity record;
        private final RunRecordMapper runRecordMapper;
        private final StudioPlatformProperties properties;
        private final ClusterInstanceIdentity identity;

        private Fixture(InternalRunLogController controller,
                        RunLogFileService runLogFileService,
                        RunRecordEntity record,
                        RunRecordMapper runRecordMapper,
                        StudioPlatformProperties properties,
                        ClusterInstanceIdentity identity) {
            this.controller = controller;
            this.runLogFileService = runLogFileService;
            this.record = record;
            this.runRecordMapper = runRecordMapper;
            this.properties = properties;
            this.identity = identity;
        }
    }
}
