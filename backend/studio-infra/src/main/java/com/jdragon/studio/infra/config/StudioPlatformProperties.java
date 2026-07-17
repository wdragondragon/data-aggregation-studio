package com.jdragon.studio.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "studio")
public class StudioPlatformProperties {
    private String aggregationHome = "../../package_all/aggregation";
    private String encryptionSecret = "studio-secret-key";
    private String timezone = "Asia/Shanghai";
    private boolean scanPluginsOnStartup = true;
    private String instanceId;
    private String podName;
    private String nodeName;
    private String workerGroupCode;
    private String workerCode = "worker-local";
    private boolean desktopRuntime = false;
    private String runtimeLogDir = "./runtime/run-logs";
    private String workerApiBaseUrl;
    private String internalApiToken = "studio-internal-token";
    private GatewayProperties gateway = new GatewayProperties();
    private PythonProperties python = new PythonProperties();
    private ModelSyncTaskProperties modelSyncTask = new ModelSyncTaskProperties();
    private DispatchProperties dispatch = new DispatchProperties();
    private ObjectStorageProperties objectStorage = new ObjectStorageProperties();
    private RunLogProperties runLog = new RunLogProperties();
    private InvocationLogProperties invocationLog = new InvocationLogProperties();
    private DatasourceHealthProperties datasourceHealth = new DatasourceHealthProperties();
    private AlertProperties alert = new AlertProperties();
    private AssistantProperties assistant = new AssistantProperties();
    private FlinkProperties flink = new FlinkProperties();

    public String getWorkerGroupCode() {
        return firstText(workerGroupCode, workerCode, "worker-local");
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

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
        private Integer schedulerBatchSize = 500;
        private Long clusterLockLeaseSeconds = 120L;
    }

    @Data
    public static class RunLogProperties {
        private String storageType = "LOCAL";
        private String objectPrefix = "studio/run-logs";
        private ObjectStorageProperties objectStorage = new ObjectStorageProperties();
    }

    @Data
    public static class InvocationLogProperties {
        private boolean enabled = true;
        private String storageType;
        private String objectPrefix = "studio/invocation-logs";
        private Integer maxLogChars = 1024 * 1024;
        private Integer maxBodyChars = 64 * 1024;
    }

    @Data
    public static class DatasourceHealthProperties {
        private boolean enabled = true;
        private ProbeProperties manual = new ProbeProperties(30, 2);
        private ProbeProperties scheduled = new ProbeProperties(30, 3);
        private Integer globalMaxConcurrency = 5;
        private Integer manualReservedConcurrency = 1;
        private Integer maxTimeoutSeconds = 120;
        private Integer scheduledIntervalMinutes = 15;
        private Integer staleAfterMinutes = 30;
        private Integer batchSize = 100;
        private Integer manualWaitRunningSeconds = 3;
        private Integer roundBudgetSeconds = 120;
        private Integer failureBackoffBaseMinutes = 30;
        private Integer failureBackoffMaxMinutes = 120;
        private Integer jitterSeconds = 60;
        private Map<String, TypeDefaultProperties> typeDefaults = new LinkedHashMap<String, TypeDefaultProperties>();
        private HistoryProperties history = new HistoryProperties();
    }

    @Data
    public static class AlertProperties {
        private boolean enabled = true;
        private boolean evaluationEnabled = true;
        private boolean deliveryEnabled = true;
        private Integer evaluationDelayMillis = 30000;
        private Integer deliveryDelayMillis = 5000;
        private Integer batchSize = 100;
        private Integer eventRetentionDays = 180;
        private Integer deliveryRetentionDays = 30;
        private WebhookProperties webhook = new WebhookProperties();
        private ElinkProperties elink = new ElinkProperties();
    }

    @Data
    public static class WebhookProperties {
        private boolean enabled = true;
        private boolean allowHttp = false;
        private List<String> allowedHosts = new ArrayList<String>();
        private Integer connectTimeoutSeconds = 3;
        private Integer requestTimeoutSeconds = 5;
        private Integer maxResponseBytes = 16 * 1024;
    }

    @Data
    public static class ElinkProperties {
        private boolean enabled = true;
        private String serviceName = "elink-message-integration";
        private String pathPrefix = "/elink";
        private Integer connectTimeoutSeconds = 3;
        private Integer requestTimeoutSeconds = 10;
        private Integer maxErrorResponseBytes = 16 * 1024;
        private Integer maxOptionResponseBytes = 1024 * 1024;
    }

    @Data
    public static class AssistantProperties {
        private LlmProperties llm = new LlmProperties();
        private SkillMemoryProperties skillMemory = new SkillMemoryProperties();
    }

    @Data
    public static class FlinkProperties {
        private boolean enabled = true;
        private String executionMode = "embedded";
        private String runtimeEndpoint;
        private Integer defaultParallelism = 1;
        private Integer maxRows = 500;
        private Integer queryTimeoutSeconds = 30;
        private Integer runtimeRegistryTtlSeconds = 300;
        private FlinkClientProperties client = new FlinkClientProperties();
        private FlinkGatewayProperties gateway = new FlinkGatewayProperties();
    }

    @Data
    public static class FlinkClientProperties {
        private String serviceName = "studio-flink";
        private String baseUrl = "http://127.0.0.1:18084";
        private String path = "";
        private Integer connectTimeoutSeconds = 10;
        private Integer requestTimeoutSeconds = 120;
    }

    @Data
    public static class FlinkGatewayProperties {
        private String baseUrl = "http://127.0.0.1:8083";
        private String restAddress;
        private Integer restPort;
        private Integer connectTimeoutSeconds = 10;
        private Integer fetchTimeoutSeconds = 30;
        private Integer maxResultPages = 1000;
    }

    @Data
    public static class LlmProperties {
        private boolean enabled = false;
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey;
        private String model = "gpt-5.4-mini";
        private Integer timeoutSeconds = 30;
        private Double temperature = 0.1D;
        private Integer maxTokens = 1200;
    }

    @Data
    public static class SkillMemoryProperties {
        private boolean enabled = true;
        private String localDir = "./runtime/assistant-skills";
        private String objectPrefix = "studio/assistant-skills";
        private Integer maxContextSkills = 6;
    }

    @Data
    public static class ProbeProperties {
        private Integer defaultTimeoutSeconds;
        private Integer maxConcurrency;

        public ProbeProperties() {
        }

        public ProbeProperties(Integer defaultTimeoutSeconds, Integer maxConcurrency) {
            this.defaultTimeoutSeconds = defaultTimeoutSeconds;
            this.maxConcurrency = maxConcurrency;
        }
    }

    @Data
    public static class TypeDefaultProperties {
        private Integer manualTimeoutSeconds;
        private Integer scheduledTimeoutSeconds;
    }

    @Data
    public static class HistoryProperties {
        private Integer retentionDays = 7;
        private Integer recentLimit = 10;
        private Integer historyQueryDefaultLimit = 1000;
        private Integer historyQueryMaxLimit = 2000;
    }

    @Data
    public static class ObjectStorageProperties {
        private String provider = "MINIO";
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String bucket;
        private String region;
        private String prefix;
        private boolean createBucket = true;
    }

    @Data
    public static class GatewayProperties {
        private boolean trustEnabled = false;
        private String sharedSecret = "change-me";
        private Long signatureExpireSeconds = 300L;
    }
}
