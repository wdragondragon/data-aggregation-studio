package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.security.StudioRequestContext;
import com.jdragon.studio.infra.security.StudioRequestContextHolder;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

@Service
public class StudioExecutionContextService {

    private final StudioAccessService accessService;

    public StudioExecutionContextService(StudioAccessService accessService) {
        this.accessService = accessService;
    }

    public void runAs(Long userId, String tenantId, Long projectId, Runnable action) {
        callAs(userId, tenantId, projectId, () -> {
            action.run();
            return null;
        });
    }

    public <T> T callAs(Long userId, String tenantId, Long projectId, Supplier<T> action) {
        StudioRequestContext previous = StudioRequestContextHolder.getContext();
        StudioRequestContext executionContext = accessService.buildExecutionContext(userId, tenantId, projectId);
        StudioRequestContextHolder.setContext(executionContext);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                StudioRequestContextHolder.clear();
            } else {
                StudioRequestContextHolder.setContext(previous);
            }
        }
    }
}
