package com.jdragon.studio.worker.operator;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.jdragon.studio.nacos.compat.props.NacosConfigProperties;
import com.jdragon.studio.nacos.compat.props.NacosRootProperties;
import com.jdragon.studio.nacos.compat.support.NacosClientManager;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Command-line Nacos operator used by the Studio plugin-runtime acceptance
 * flow.  It performs a read/backup/CAS publish or a guarded CAS restore; no
 * configuration body or credential is written to stdout.
 */
public final class NacosPluginRuntimeConfigTool {

    static final String EXPECTED_SERVER_ADDR = "127.0.0.1:8848";
    static final String EXPECTED_NAMESPACE = "ZCYY";
    static final String EXPECTED_GROUP = "ZCYY_GROUP";
    static final String EXPECTED_DATA_ID = "studio-worker-prod.yaml";
    static final String EXPECTED_PROFILE = "prod";
    static final String EXPECTED_APPLICATION = "studio-worker";

    private static final Set<String> OPTION_KEYS = Set.of(
            "tool.action", "tool.state-file", "tool.timeout-ms", "tool.server-addr", "tool.profile",
            "tool.data-id", "tool.group", "tool.namespace", "tool.aggregation-home", "tool.runtime-version",
            "tool.plugin-bucket", "tool.plugin-prefix", "tool.plugin-channel",
            "tool.refresh-interval-seconds", "tool.refresh-jitter-seconds", "tool.cold-load-timeout-seconds",
            "tool.object-storage-endpoint");

    private NacosPluginRuntimeConfigTool() {
    }

    public static void main(String[] args) throws Exception {
        String action = actionHint(args);
        try {
            execute(Options.parse(args));
        }
        catch (Exception ex) {
            // Never expose a Nacos client exception: it can contain an endpoint,
            // username, or other provider-specific details.
            emitFailure(action, failureCategory(ex));
            throw new IllegalStateException("Nacos plugin config operation failed");
        }
    }

    private static void execute(Options options) throws Exception {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(ToolConfiguration.class)
                .web(WebApplicationType.NONE)
                .registerShutdownHook(false)
                .properties("spring.main.banner-mode=off", "spring.main.log-startup-info=false",
                        "studio.operator.nacos-plugin-config=true",
                        "logging.level.root=OFF", "logging.level.com.alibaba.nacos=OFF",
                        "logging.level.com.jdragon.studio.nacos=OFF");
        try (ConfigurableApplicationContext context = builder.run(bootstrapArgs())) {
            NacosRootProperties root = context.getBean(NacosRootProperties.class);
            NacosConfigProperties config = context.getBean(NacosConfigProperties.class);
            validateBoundSource(context.getEnvironment(), root, config, options);
            NacosClientManager clients = context.getBean(NacosClientManager.class);
            NacosConfigProperties clientConfig = copyConfig(config);
            ConfigService service = clients.getConfigService(root, clientConfig);
            try {
                NacosPluginRuntimeConfigTransaction.ConfigClient client = new ConfigServiceClient(service, options.timeoutMs);
                NacosPluginRuntimeConfigTransaction transaction = new NacosPluginRuntimeConfigTransaction(client);
                NacosPluginRuntimeConfigTransaction.Target target =
                        new NacosPluginRuntimeConfigTransaction.Target(EXPECTED_DATA_ID, EXPECTED_GROUP,
                                EXPECTED_NAMESPACE);
                switch (options.action) {
                    case "apply" -> {
                        NacosPluginRuntimeConfigTransaction.ApplyResult result = transaction.apply(target,
                                options.override(), options.stateFile);
                        emit("apply", "applied");
                    }
                    case "restore" -> {
                        NacosPluginRuntimeConfigTransaction.RestoreResult result = transaction.restore(target,
                                options.stateFile);
                        emit("restore", "restored");
                    }
                    case "verify" -> {
                        NacosPluginRuntimeConfigTransaction.VerifyResult result = transaction.verifyApplied(target,
                                options.stateFile);
                        emit("verify", result.matches() ? "matched" : "mismatch");
                        if (!result.matches()) {
                            throw new NacosPluginRuntimeConfigTransaction.CasConflictException(
                                    "Nacos config does not match the recorded test revision");
                        }
                    }
                    case "verify-restored" -> {
                        NacosPluginRuntimeConfigTransaction.VerifyResult result =
                                transaction.verifyRestored(target, options.stateFile);
                        emit("verify-restored", result.matches() ? "matched" : "mismatch");
                        if (!result.matches()) {
                            throw new NacosPluginRuntimeConfigTransaction.CasConflictException(
                                    "Nacos config does not match the recorded original revision");
                        }
                    }
                    default -> throw new IllegalArgumentException("Unsupported Nacos plugin-runtime action");
                }
            }
            finally {
                try {
                    service.shutDown();
                }
                catch (NacosException ignored) {
                    // Do not expose client details in the operator output.
                }
            }
        }
    }

    private static String[] bootstrapArgs() {
        return new String[] {
                "--spring.application.name=" + EXPECTED_APPLICATION,
                "--spring.profiles.active=" + EXPECTED_PROFILE,
                // The operator must not import and accidentally mutate a remote
                // configuration before its source binding has been checked.
                "--spring.config.import=",
                "--spring.cloud.nacos.config.enabled=true",
                "--spring.cloud.nacos.config.server-addr=" + EXPECTED_SERVER_ADDR,
                "--spring.cloud.nacos.config.namespace=" + EXPECTED_NAMESPACE,
                "--spring.cloud.nacos.config.group=" + EXPECTED_GROUP,
                "--spring.cloud.nacos.config.file-extension=yaml",
                "--spring.cloud.nacos.config.refresh-enabled=false"
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "studio.operator.nacos-plugin-config", havingValue = "true")
    @EnableConfigurationProperties({NacosRootProperties.class, NacosConfigProperties.class})
    @Import(NacosClientManager.class)
    static class ToolConfiguration {
    }

    static void validateBoundSource(ConfigurableEnvironment environment, NacosRootProperties root,
                                    NacosConfigProperties config, Options options) {
        if (!EXPECTED_SERVER_ADDR.equals(trim(config.getServerAddr()))
                || !EXPECTED_NAMESPACE.equals(trim(config.getNamespace()))
                || !EXPECTED_GROUP.equals(trim(config.getGroup()))
                || !"yaml".equalsIgnoreCase(trim(config.getFileExtension()))
                || !config.isEnabled()) {
            throw new IllegalStateException("Nacos operator source binding is not the approved local target");
        }
        String application = environment.getProperty("spring.application.name");
        if (!EXPECTED_APPLICATION.equals(application)
                || !EXPECTED_PROFILE.equals(environment.getProperty("spring.profiles.active"))
                || hasText(environment.getProperty("spring.config.import"))) {
            throw new IllegalStateException("Nacos operator profile or import source is not approved");
        }
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length != 1 || !EXPECTED_PROFILE.equals(activeProfiles[0])) {
            throw new IllegalStateException("Nacos operator active profile is not approved");
        }
        boolean userPasswordPair = hasText(root.getUsername()) && hasText(root.getPassword());
        boolean accessKeyPair = hasText(config.getAccessKey()) && hasText(config.getSecretKey());
        if ((!userPasswordPair && (hasText(root.getUsername()) || hasText(root.getPassword())))
                || (!accessKeyPair && (hasText(config.getAccessKey()) || hasText(config.getSecretKey())))
                || (!userPasswordPair && !accessKeyPair)) {
            throw new IllegalStateException("Nacos operator authentication source is incomplete");
        }
        options.requireApprovedTarget();
    }

    private static NacosConfigProperties copyConfig(NacosConfigProperties source) {
        NacosConfigProperties copy = new NacosConfigProperties();
        copy.setEnabled(true);
        copy.setServerAddr(EXPECTED_SERVER_ADDR);
        copy.setNamespace(EXPECTED_NAMESPACE);
        copy.setGroup(EXPECTED_GROUP);
        copy.setFileExtension("yaml");
        copy.setEncode(source.getEncode());
        copy.setAccessKey(source.getAccessKey());
        copy.setSecretKey(source.getSecretKey());
        copy.setRefreshEnabled(false);
        return copy;
    }

    private static void emit(String action, String status) {
        System.out.println("NACOS_PLUGIN_CONFIG_OP action=" + action + " status=" + status);
    }

    private static void emitFailure(String action, String category) {
        System.err.println("NACOS_PLUGIN_CONFIG_OP action=" + action + " status=failed category=" + category);
    }

    private static String failureCategory(Exception ex) {
        if (ex instanceof NacosPluginRuntimeConfigTransaction.CasConflictException) {
            return "cas-conflict";
        }
        if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
            return "validation";
        }
        return "operation";
    }

    private static String actionHint(String[] args) {
        if (args != null) {
            for (String arg : args) {
                if (arg != null && arg.startsWith("--tool.action=")) {
                    String value = arg.substring("--tool.action=".length()).trim().toLowerCase(Locale.ROOT);
                    if (value.equals("apply") || value.equals("restore") || value.equals("verify")
                            || value.equals("verify-restored")) {
                        return value;
                    }
                }
            }
        }
        return "unknown";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static final class ConfigServiceClient implements NacosPluginRuntimeConfigTransaction.ConfigClient {
        private final ConfigService service;
        private final long timeoutMs;

        private ConfigServiceClient(ConfigService service, long timeoutMs) {
            this.service = Objects.requireNonNull(service, "service");
            this.timeoutMs = timeoutMs;
        }

        @Override
        public String getConfig(String dataId, String group) throws NacosException {
            return service.getConfig(dataId, group, timeoutMs);
        }

        @Override
        public boolean publishConfigCas(String dataId, String group, String content, String expectedMd5, String type)
                throws NacosException {
            return service.publishConfigCas(dataId, group, content, expectedMd5, type);
        }
    }

    static final class Options {
        private final String action;
        private final Path stateFile;
        private final long timeoutMs;
        private final Map<String, String> values;

        private Options(String action, Path stateFile, long timeoutMs, Map<String, String> values) {
            this.action = action;
            this.stateFile = stateFile;
            this.timeoutMs = timeoutMs;
            this.values = values;
        }

        static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String arg : args == null ? new String[0] : args) {
                if (arg == null || !arg.startsWith("--") || !arg.contains("=")) {
                    throw new IllegalArgumentException("Nacos operator arguments must use the approved form");
                }
                int split = arg.indexOf('=');
                String key = arg.substring(2, split);
                if (!OPTION_KEYS.contains(key) || values.putIfAbsent(key, arg.substring(split + 1)) != null) {
                    throw new IllegalArgumentException("Nacos operator argument is not approved");
                }
            }
            String action = values.get("tool.action");
            if (!hasText(action)) {
                throw new IllegalArgumentException("--tool.action=apply|restore|verify is required");
            }
            action = action.trim().toLowerCase(Locale.ROOT);
            if (!action.equals("apply") && !action.equals("restore") && !action.equals("verify")
                    && !action.equals("verify-restored")) {
                throw new IllegalArgumentException("Unsupported Nacos plugin-runtime action");
            }
            Path state = path(values.get("tool.state-file"));
            if (state == null) {
                throw new IllegalArgumentException("--tool.state-file is required");
            }
            long timeout = parseLong(values, "tool.timeout-ms", 10_000L);
            if (timeout <= 0L) {
                throw new IllegalArgumentException("--tool.timeout-ms must be positive");
            }
            if ("apply".equals(action)) {
                require(values, "tool.aggregation-home");
                require(values, "tool.runtime-version");
                require(values, "tool.plugin-bucket");
                require(values, "tool.plugin-prefix");
                require(values, "tool.plugin-channel");
            }
            Options options = new Options(action, state, timeout, values);
            options.requireApprovedTarget();
            return options;
        }

        private void requireApprovedTarget() {
            fixed(values, "tool.server-addr", EXPECTED_SERVER_ADDR);
            fixed(values, "tool.profile", EXPECTED_PROFILE);
            fixed(values, "tool.data-id", EXPECTED_DATA_ID);
            fixed(values, "tool.group", EXPECTED_GROUP);
            fixed(values, "tool.namespace", EXPECTED_NAMESPACE);
        }

        private static void fixed(Map<String, String> values, String key, String expected) {
            if (values.containsKey(key) && !expected.equals(values.get(key).trim())) {
                throw new IllegalStateException("Nacos operator target does not match the approved local target");
            }
        }

        NacosPluginRuntimeConfigTransaction.PluginRuntimeOverride override() {
            String endpoint = values.get("tool.object-storage-endpoint");
            if (hasText(endpoint)
                    && !NacosPluginRuntimeConfigTransaction.OFFLINE_TEST_ENDPOINT.equals(endpoint.trim())) {
                throw new IllegalArgumentException("Only the fixed loopback OSS outage endpoint is approved");
            }
            return new NacosPluginRuntimeConfigTransaction.PluginRuntimeOverride(
                    values.get("tool.aggregation-home"), values.get("tool.runtime-version"),
                    values.get("tool.plugin-bucket"), values.get("tool.plugin-prefix"),
                    values.get("tool.plugin-channel"), integer(values, "tool.refresh-interval-seconds", 30),
                    integer(values, "tool.refresh-jitter-seconds", 0),
                    integer(values, "tool.cold-load-timeout-seconds", 300), endpoint);
        }

        private static void require(Map<String, String> values, String name) {
            if (!hasText(values.get(name))) {
                throw new IllegalArgumentException("--" + name + " is required for apply");
            }
        }

        private static int integer(Map<String, String> values, String key, int fallback) {
            String value = values.get(key);
            return hasText(value) ? Integer.parseInt(value) : fallback;
        }

        private static long parseLong(Map<String, String> values, String key, long fallback) {
            String value = values.get(key);
            return hasText(value) ? Long.parseLong(value) : fallback;
        }

        private static Path path(String value) {
            return hasText(value) ? Path.of(value).toAbsolutePath().normalize() : null;
        }
    }
}
