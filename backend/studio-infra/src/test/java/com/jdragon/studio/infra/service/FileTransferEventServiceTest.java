package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jdragon.studio.dto.model.FileTransferRunView;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.entity.FileTransferRunItemEntity;
import com.jdragon.studio.infra.mapper.FileTransferRunItemMapper;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileTransferEventServiceTest {

    @BeforeAll
    static void initializeLambdaMetadata() {
        initialize(FileTransferRunEntity.class);
        initialize(FileTransferRunItemEntity.class);
    }

    @Test
    void shouldPublishOnlyToEmittersInCurrentTenantAndProject() throws Exception {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        FileTransferRunService runService = mock(FileTransferRunService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService projectAccess = mock(ProjectResourceAccessService.class);
        AtomicLong projectId = new AtomicLong(10L);
        when(securityService.currentTenantId()).thenReturn("default");
        when(projectAccess.requireCurrentProjectId()).thenAnswer(ignored -> projectId.get());

        SseEmitter project10Emitter = mock(SseEmitter.class);
        SseEmitter project20Emitter = mock(SseEmitter.class);
        FileTransferEventService service = service(runMapper, itemMapper, runService,
                securityService, projectAccess, project10Emitter, project20Emitter);
        service.connect();
        projectId.set(20L);
        service.connect();
        clearInvocations(project10Emitter, project20Emitter);

        FileTransferRunEntity run = run(100L, 20L, 0L);
        FileTransferRunView view = new FileTransferRunView();
        view.setId(run.getId());
        when(runMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(run);
        when(runService.toRunView(run)).thenReturn(view);

        service.publishRunChanged(run.getId());

        verify(project20Emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(project10Emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void shouldPublishDatabaseChangesOncePerFingerprintAndRemoveCompletedEmitter() throws Exception {
        FileTransferRunMapper runMapper = mock(FileTransferRunMapper.class);
        FileTransferRunItemMapper itemMapper = mock(FileTransferRunItemMapper.class);
        FileTransferRunService runService = mock(FileTransferRunService.class);
        StudioSecurityService securityService = mock(StudioSecurityService.class);
        ProjectResourceAccessService projectAccess = mock(ProjectResourceAccessService.class);
        when(securityService.currentTenantId()).thenReturn("default");
        when(projectAccess.requireCurrentProjectId()).thenReturn(10L);

        SseEmitter emitter = mock(SseEmitter.class);
        FileTransferEventService service = service(runMapper, itemMapper, runService,
                securityService, projectAccess, emitter);
        service.connect();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onCompletion(completion.capture());
        clearInvocations(emitter);

        FileTransferRunEntity run = run(100L, 10L, 0L);
        FileTransferRunView view = new FileTransferRunView();
        view.setId(run.getId());
        when(runMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.singletonList(run));
        when(itemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());
        when(runService.toRunView(run)).thenReturn(view);

        service.publishDatabaseChanges();
        service.publishDatabaseChanges();
        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));

        run.setTransferredBytes(1L);
        run.setUpdatedAt(run.getUpdatedAt().plusSeconds(1L));
        service.publishDatabaseChanges();
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));

        completion.getValue().run();
        run.setTransferredBytes(2L);
        service.publishDatabaseChanges();
        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    private FileTransferEventService service(FileTransferRunMapper runMapper,
                                             FileTransferRunItemMapper itemMapper,
                                             FileTransferRunService runService,
                                             StudioSecurityService securityService,
                                             ProjectResourceAccessService projectAccess,
                                             SseEmitter... emitters) {
        return new FileTransferEventService(runMapper, itemMapper, runService, securityService, projectAccess) {
            private int index;

            @Override
            SseEmitter createEmitter() {
                return emitters[index++];
            }
        };
    }

    private FileTransferRunEntity run(Long id, Long projectId, Long transferredBytes) {
        FileTransferRunEntity run = new FileTransferRunEntity();
        run.setId(id);
        run.setTenantId("default");
        run.setProjectId(projectId);
        run.setStatus("RUNNING");
        run.setTransferredBytes(transferredBytes);
        run.setUpdatedAt(LocalDateTime.of(2026, 8, 8, 10, 0));
        return run;
    }

    private static void initialize(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) == null) {
            TableInfoHelper.initTableInfo(
                    new MapperBuilderAssistant(new MybatisConfiguration(), "file-transfer-event-test"),
                    entityType);
        }
    }
}
