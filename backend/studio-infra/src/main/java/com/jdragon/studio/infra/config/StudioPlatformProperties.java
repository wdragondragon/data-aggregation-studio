package com.jdragon.studio.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
}
