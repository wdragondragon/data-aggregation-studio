package com.jdragon.studio.worker.operator;

import com.jdragon.studio.nacos.compat.props.NacosConfigProperties;
import com.jdragon.studio.nacos.compat.props.NacosRootProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NacosPluginRuntimeConfigToolTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldAcceptOnlyTheApprovedLocalSourceAndProfile() {
        NacosPluginRuntimeConfigTool.Options options = restoreOptions();
        MockEnvironment environment = approvedEnvironment();
        NacosRootProperties root = authenticatedRoot();
        NacosConfigProperties config = approvedConfig();

        assertThatCode(() -> NacosPluginRuntimeConfigTool.validateBoundSource(environment, root, config, options))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldAcceptTheReadOnlyRestoredRevisionVerificationAction() {
        assertThatCode(() -> NacosPluginRuntimeConfigTool.Options.parse(new String[] {
                "--tool.action=verify-restored",
                "--tool.state-file=" + tempDir.resolve("state.json")
        })).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectAnExplicitTargetOverrideBeforeStartingAClient() {
        assertThatThrownBy(() -> NacosPluginRuntimeConfigTool.Options.parse(new String[] {
                "--tool.action=restore",
                "--tool.state-file=" + tempDir.resolve("state.json"),
                "--tool.namespace=unapproved"
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Nacos operator target does not match the approved local target");
    }

    @Test
    void shouldRejectUnknownSpringOverridesRatherThanPassThemToBootstrap() {
        assertThatThrownBy(() -> NacosPluginRuntimeConfigTool.Options.parse(new String[] {
                "--tool.action=restore",
                "--tool.state-file=" + tempDir.resolve("state.json"),
                "--spring.profiles.active=test"
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Nacos operator argument is not approved");
    }

    @Test
    void shouldRejectAnArbitraryObjectStorageEndpoint() {
        assertThatThrownBy(() -> NacosPluginRuntimeConfigTool.Options.parse(new String[] {
                "--tool.action=apply",
                "--tool.state-file=" + tempDir.resolve("state.json"),
                "--tool.aggregation-home=C:\\runtime\\plugins",
                "--tool.runtime-version=1.0_jdk17-SNAPSHOT",
                "--tool.plugin-bucket=plugin-bucket",
                "--tool.plugin-prefix=aggregation-plugins",
                "--tool.plugin-channel=production",
                "--tool.object-storage-endpoint=https://unapproved.example.invalid"
        }).override())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only the fixed loopback OSS outage endpoint is approved");
    }

    @Test
    void shouldRejectWrongEffectiveSourceAndAdditionalProfiles() {
        NacosPluginRuntimeConfigTool.Options options = restoreOptions();
        MockEnvironment environment = approvedEnvironment();
        environment.setActiveProfiles(NacosPluginRuntimeConfigTool.EXPECTED_PROFILE, "extra");

        assertThatThrownBy(() -> NacosPluginRuntimeConfigTool.validateBoundSource(
                environment, authenticatedRoot(), approvedConfig(), options))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Nacos operator active profile is not approved");
    }

    @Test
    void shouldRequireACompleteAuthenticationSource() {
        NacosRootProperties root = new NacosRootProperties();
        root.setUsername("operator-user");

        assertThatThrownBy(() -> NacosPluginRuntimeConfigTool.validateBoundSource(
                approvedEnvironment(), root, approvedConfig(), restoreOptions()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Nacos operator authentication source is incomplete");
    }

    @Test
    void shouldEmitOnlySanitizedFailureMetadata() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            assertThatThrownBy(() -> NacosPluginRuntimeConfigTool.main(new String[] {
                    "--tool.action=restore",
                    "--tool.state-file=" + tempDir.resolve("state.json"),
                    "--spring.profiles.active=test"
            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Nacos plugin config operation failed")
                    .hasNoCause();
        }
        finally {
            System.setErr(originalErr);
        }

        assertThat(captured.toString(StandardCharsets.UTF_8))
                .isEqualTo("NACOS_PLUGIN_CONFIG_OP action=restore status=failed category=validation"
                        + System.lineSeparator());
    }

    private NacosPluginRuntimeConfigTool.Options restoreOptions() {
        return NacosPluginRuntimeConfigTool.Options.parse(new String[] {
                "--tool.action=restore",
                "--tool.state-file=" + tempDir.resolve("state.json"),
                "--tool.server-addr=" + NacosPluginRuntimeConfigTool.EXPECTED_SERVER_ADDR,
                "--tool.profile=" + NacosPluginRuntimeConfigTool.EXPECTED_PROFILE,
                "--tool.data-id=" + NacosPluginRuntimeConfigTool.EXPECTED_DATA_ID,
                "--tool.group=" + NacosPluginRuntimeConfigTool.EXPECTED_GROUP,
                "--tool.namespace=" + NacosPluginRuntimeConfigTool.EXPECTED_NAMESPACE
        });
    }

    private static MockEnvironment approvedEnvironment() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", NacosPluginRuntimeConfigTool.EXPECTED_APPLICATION)
                .withProperty("spring.profiles.active", NacosPluginRuntimeConfigTool.EXPECTED_PROFILE)
                .withProperty("spring.config.import", "");
        environment.setActiveProfiles(NacosPluginRuntimeConfigTool.EXPECTED_PROFILE);
        return environment;
    }

    private static NacosRootProperties authenticatedRoot() {
        NacosRootProperties root = new NacosRootProperties();
        root.setUsername("operator-user");
        root.setPassword("opaque-test-value");
        return root;
    }

    private static NacosConfigProperties approvedConfig() {
        NacosConfigProperties config = new NacosConfigProperties();
        config.setEnabled(true);
        config.setServerAddr(NacosPluginRuntimeConfigTool.EXPECTED_SERVER_ADDR);
        config.setNamespace(NacosPluginRuntimeConfigTool.EXPECTED_NAMESPACE);
        config.setGroup(NacosPluginRuntimeConfigTool.EXPECTED_GROUP);
        config.setFileExtension("yaml");
        return config;
    }
}
