package com.jdragon.studio.test;

import com.jdragon.studio.infra.config.StudioPlatformProperties;
import com.jdragon.studio.test.support.StudioApiRegressionTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopRuntimeInitializationRegressionTest extends StudioApiRegressionTestSupport {

    @Autowired
    private StudioPlatformProperties studioPlatformProperties;

    @Test
    void initializeResetShouldBootstrapDefaultProjectWorkerBindingInDesktopMode() {
        boolean originalDesktopRuntime = studioPlatformProperties.isDesktopRuntime();
        String originalWorkerCode = studioPlatformProperties.getWorkerCode();
        String originalWorkerGroupCode = studioPlatformProperties.getWorkerGroupCode();
        try {
            studioPlatformProperties.setDesktopRuntime(true);
            studioPlatformProperties.setWorkerCode("studio-desktop-worker");
            studioPlatformProperties.setWorkerGroupCode("studio-desktop-worker");
            studioInitializationService.initialize(true);

            Long projectId = jdbcTemplate.queryForObject(
                    "select id from studio_project where tenant_id = 'default' and project_code = 'default' limit 1",
                    Long.class);
            assertThat(projectId).isNotNull();

            Integer bindingCount = jdbcTemplate.queryForObject(
                    "select count(1) from studio_project_worker_binding " +
                            "where tenant_id = 'default' and project_id = ? and worker_code = 'studio-desktop-worker' and enabled = 1",
                    Integer.class,
                    projectId);

            assertThat(bindingCount).isEqualTo(1);
        } finally {
            studioPlatformProperties.setDesktopRuntime(originalDesktopRuntime);
            studioPlatformProperties.setWorkerCode(originalWorkerCode);
            studioPlatformProperties.setWorkerGroupCode(originalWorkerGroupCode);
        }
    }
}
