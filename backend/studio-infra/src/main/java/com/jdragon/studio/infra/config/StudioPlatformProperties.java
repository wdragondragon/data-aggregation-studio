package com.jdragon.studio.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "studio")
public class StudioPlatformProperties {
    private String aggregationHome = "../../package_all/aggregation";
    private String encryptionSecret = "studio-secret-key";
    private String timezone = "Asia/Shanghai";
    private boolean scanPluginsOnStartup = true;
    private String workerCode = "worker-local";
    private boolean desktopRuntime = false;
    private String runtimeLogDir = "./runtime/run-logs";
    private String workerApiBaseUrl;
    private String internalApiToken = "studio-internal-token";
    private GatewayProperties gateway = new GatewayProperties();
    private PythonProperties python = new PythonProperties();
    private ModelSyncTaskProperties modelSyncTask = new ModelSyncTaskProperties();
    private DispatchProperties dispatch = new DispatchProperties();

    @Data
    public static class PythonProperties {
        private String executable;
        private List<String> executableArgs = new ArrayList<String>();
        private Long executionTimeoutSeconds = 120L;
        private String tempDir;
    }

    @Data
    public static class ModelSyncTaskProperties {
        private Integer maxConcurrency = 1;
    }

    @Data
    public static class DispatchProperties {
        private Long workerOfflineGraceMinutes = 120L;
        private Long dispatchLeaseMinutes = 10L;
        private Integer workerSchedulerPoolSize = 4;
    }

    @Data
    public static class GatewayProperties {
        private boolean trustEnabled = false;
        private String sharedSecret = "change-me";
        private Long signatureExpireSeconds = 300L;
    }
}
