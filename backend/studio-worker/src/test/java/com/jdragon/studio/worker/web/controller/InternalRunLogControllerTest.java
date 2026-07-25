package com.jdragon.studio.worker.web.controller;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.RunLogView;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.mapper.RunRecordMapper;
import com.jdragon.studio.infra.service.ClusterInstanceIdentity;
import com.jdragon.studio.worker.runtime.log.RunLogFileService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        return new Fixture(controller, runLogFileService, record);
    }

    private static final class Fixture {
        private final InternalRunLogController controller;
        private final RunLogFileService runLogFileService;
        private final RunRecordEntity record;

        private Fixture(InternalRunLogController controller,
                        RunLogFileService runLogFileService,
                        RunRecordEntity record) {
            this.controller = controller;
            this.runLogFileService = runLogFileService;
            this.record = record;
        }
    }
}
