package com.jdragon.studio.infra.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudioEncryptionRotationServiceTest {

    private static final String OLD_SECRET = "legacy-key-for-rotation-tests";
    private static final String NEW_SECRET = "new-key-for-rotation-tests";

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private StudioEncryptionRotationService service;
    private EncryptionService oldEncryption;
    private EncryptionService newEncryption;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbcTemplate = new JdbcTemplate(dataSource);
        service = new StudioEncryptionRotationService(jdbcTemplate, new ObjectMapper());
        oldEncryption = EncryptionService.forSecret(OLD_SECRET);
        newEncryption = EncryptionService.forSecret(NEW_SECRET);
        createSchema(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @Test
    void shouldDryRunApplyAndRemainIdempotentAcrossRawAndEmbeddedJsonCiphertexts() {
        String endpointCipher = oldEncryption.encrypt("http://worker.internal:18081");
        String endpointHeadersCipher = oldEncryption.encrypt("{\"X-SLB-Token\":\"header-secret\"}");
        String endpointTokenCipher = oldEncryption.encrypt("runtime-token");
        jdbcTemplate.update("insert into studio_runtime_endpoint "
                        + "(id, endpoint_ciphertext, headers_ciphertext, token_ciphertext) values (?, ?, ?, ?)",
                1L, endpointCipher, endpointHeadersCipher, endpointTokenCipher);

        String alertEndpointCipher = oldEncryption.encrypt("https://hooks.example.test/alert");
        String alreadyNewHeadersCipher = newEncryption.encrypt("{\"X-Current\":\"new-key\"}");
        jdbcTemplate.update("insert into studio_alert_channel "
                        + "(id, endpoint_ciphertext, headers_ciphertext, signing_secret_ciphertext) values (?, ?, ?, ?)",
                2L, alertEndpointCipher, alreadyNewHeadersCipher, null);

        String responseCipher = oldEncryption.encrypt("{\"success\":true}");
        jdbcTemplate.update("insert into studio_runtime_idempotency (id, response_body_ciphertext) values (?, ?)",
                3L, responseCipher);
        String dispatchCipher = oldEncryption.encrypt("{\"arguments\":{\"accessToken\":\"secret\"}}");
        jdbcTemplate.update("insert into dispatch_task (id, protected_payload_ciphertext) values (?, ?)",
                7L, dispatchCipher);

        String datasourcePasswordCipher = oldEncryption.encrypt("datasource-password");
        String datasourceTokenCipher = oldEncryption.encrypt("datasource-token");
        String datasourceJson = "{\"password\":\"ENC(" + datasourcePasswordCipher
                + ")\",\"nested\":{\"token\":\"ENC(" + datasourceTokenCipher + ")\"}}";
        jdbcTemplate.update("insert into datasource_definition (id, technical_metadata) values (?, ?)",
                4L, datasourceJson);

        String modelBodyCipher = oldEncryption.encrypt("model-body-secret");
        String modelJson = "{\"readerOptions\":{\"requestBody\":\"<token>ENC("
                + modelBodyCipher + ")</token>\"}}";
        jdbcTemplate.update("insert into data_model (id, technical_metadata) values (?, ?)", 5L, modelJson);

        String overrideCipher = oldEncryption.encrypt("override-secret");
        String sourceBindingsJson = "[{\"readerOptions\":{\"header\":{\"Authorization\":\"ENC("
                + overrideCipher + ")\"}}}]";
        jdbcTemplate.update("insert into collection_task_definition (id, source_bindings_json) values (?, ?)",
                6L, sourceBindingsJson);

        StudioEncryptionRotationService.RotationReport dryRun = service.rotate(
                OLD_SECRET, NEW_SECRET, false);

        assertThat(dryRun.isApplied()).isFalse();
        assertThat(dryRun.getRotatedValues()).isEqualTo(10);
        assertThat(dryRun.getAlreadyCurrentValues()).isEqualTo(1);
        assertThat(dryRun.getCandidateRows()).isEqualTo(9);
        assertThat(dryRun.getUpdatedRows()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select endpoint_ciphertext from studio_runtime_endpoint where id = 1", String.class))
                .isEqualTo(endpointCipher);

        StudioEncryptionRotationService.RotationReport applied = service.rotate(
                OLD_SECRET, NEW_SECRET, true);

        assertThat(applied.isApplied()).isTrue();
        assertThat(applied.getRotatedValues()).isEqualTo(10);
        assertThat(applied.getAlreadyCurrentValues()).isEqualTo(1);
        assertThat(applied.getCandidateRows()).isEqualTo(9);
        assertThat(applied.getUpdatedRows()).isEqualTo(9);
        assertThat(newEncryption.decrypt(jdbcTemplate.queryForObject(
                "select endpoint_ciphertext from studio_runtime_endpoint where id = 1", String.class)))
                .isEqualTo("http://worker.internal:18081");
        assertThat(jdbcTemplate.queryForObject(
                "select headers_ciphertext from studio_alert_channel where id = 2", String.class))
                .isEqualTo(alreadyNewHeadersCipher);
        assertThat(newEncryption.decrypt(jdbcTemplate.queryForObject(
                "select protected_payload_ciphertext from dispatch_task where id = 7", String.class)))
                .contains("accessToken");
        assertRotatedJson("datasource_definition", "technical_metadata", 4L,
                datasourcePasswordCipher, newEncryption.encrypt("datasource-password"));
        assertRotatedJson("data_model", "technical_metadata", 5L,
                modelBodyCipher, newEncryption.encrypt("model-body-secret"));
        assertRotatedJson("collection_task_definition", "source_bindings_json", 6L,
                overrideCipher, newEncryption.encrypt("override-secret"));

        StudioEncryptionRotationService.RotationReport repeated = service.rotate(
                OLD_SECRET, NEW_SECRET, true);
        assertThat(repeated.getRotatedValues()).isZero();
        assertThat(repeated.getAlreadyCurrentValues()).isEqualTo(11);
        assertThat(repeated.getCandidateRows()).isZero();
        assertThat(repeated.getUpdatedRows()).isZero();
    }

    @Test
    void shouldRollbackEarlierUpdatesWhenAnyStoredValueCannotBeDecrypted() {
        String endpointCipher = oldEncryption.encrypt("http://worker.internal:18081");
        jdbcTemplate.update("insert into studio_runtime_endpoint (id, endpoint_ciphertext) values (?, ?)",
                1L, endpointCipher);
        jdbcTemplate.update("insert into studio_alert_channel (id, endpoint_ciphertext) values (?, ?)",
                2L, "not-valid-ciphertext");

        assertThatThrownBy(() -> service.rotate(OLD_SECRET, NEW_SECRET, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be decrypted with either key")
                .hasMessageContaining("studio_alert_channel.endpoint_ciphertext")
                .satisfies(exception -> assertThat(exception.getMessage())
                        .doesNotContain("not-valid-ciphertext"));

        assertThat(jdbcTemplate.queryForObject(
                "select endpoint_ciphertext from studio_runtime_endpoint where id = 1", String.class))
                .isEqualTo(endpointCipher);
    }

    @Test
    void shouldQuarantineUnreadableRuntimeEndpointWithoutBlockingRotation() {
        jdbcTemplate.update("insert into studio_runtime_endpoint "
                        + "(id, endpoint_ciphertext, headers_ciphertext, token_ciphertext, enabled) values (?, ?, ?, ?, ?)",
                1L, "not-valid-ciphertext", oldEncryption.encrypt("{}"), oldEncryption.encrypt("token"), 1);
        jdbcTemplate.update("insert into studio_alert_channel (id, endpoint_ciphertext) values (?, ?)",
                2L, oldEncryption.encrypt("https://hooks.example.test/alert"));

        StudioEncryptionRotationService.RotationReport dryRun = service.rotate(OLD_SECRET, NEW_SECRET, false);
        assertThat(dryRun.getQuarantinedRuntimeEndpoints()).isEqualTo(1);
        assertThat(dryRun.getRotatedValues()).isEqualTo(3);

        StudioEncryptionRotationService.RotationReport applied = service.rotate(OLD_SECRET, NEW_SECRET, true);
        assertThat(applied.getQuarantinedRuntimeEndpoints()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select enabled from studio_runtime_endpoint where id = 1", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("select endpoint_ciphertext from studio_runtime_endpoint where id = 1", String.class))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("select headers_ciphertext from studio_runtime_endpoint where id = 1", String.class))
                .isNull();
        assertThat(jdbcTemplate.queryForObject("select token_ciphertext from studio_runtime_endpoint where id = 1", String.class))
                .isNull();
        assertThat(newEncryption.decrypt(jdbcTemplate.queryForObject(
                "select endpoint_ciphertext from studio_alert_channel where id = 2", String.class)))
                .isEqualTo("https://hooks.example.test/alert");
    }

    @Test
    void shouldRejectMalformedEncryptedMarkersWithoutWritingOtherRows() {
        String endpointCipher = oldEncryption.encrypt("http://worker.internal:18081");
        jdbcTemplate.update("insert into studio_runtime_endpoint (id, endpoint_ciphertext) values (?, ?)",
                1L, endpointCipher);
        jdbcTemplate.update("insert into datasource_definition (id, technical_metadata) values (?, ?)",
                2L, "{\"password\":\"ENC(not-valid-ciphertext)\"}");

        assertThatThrownBy(() -> service.rotate(OLD_SECRET, NEW_SECRET, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be decrypted with either key")
                .hasMessageContaining("datasource_definition.technical_metadata")
                .satisfies(exception -> assertThat(exception.getMessage())
                        .doesNotContain("not-valid-ciphertext"));

        assertThat(jdbcTemplate.queryForObject(
                "select endpoint_ciphertext from studio_runtime_endpoint where id = 1", String.class))
                .isEqualTo(endpointCipher);
    }

    @Test
    void shouldRejectUnclosedEncryptedMarkers() {
        jdbcTemplate.update("insert into datasource_definition (id, technical_metadata) values (?, ?)",
                1L, "{\"password\":\"ENC(unclosed\"}");

        assertThatThrownBy(() -> service.rotate(OLD_SECRET, NEW_SECRET, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Malformed encrypted marker")
                .hasMessageContaining("datasource_definition.technical_metadata")
                .satisfies(exception -> assertThat(exception.getMessage())
                        .doesNotContain("unclosed"));
    }

    @Test
    void shouldSkipTargetsAbsentFromLegacySchemaWithoutHidingAvailableValues() {
        SingleConnectionDataSource legacyDataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            JdbcTemplate legacyJdbc = new JdbcTemplate(legacyDataSource);
            legacyJdbc.execute("create table datasource_definition (id integer primary key, technical_metadata text)");
            String passwordCipher = oldEncryption.encrypt("legacy-password");
            legacyJdbc.update("insert into datasource_definition (id, technical_metadata) values (?, ?)",
                    1L, "{\"password\":\"ENC(" + passwordCipher + ")\"}");
            StudioEncryptionRotationService legacyService = new StudioEncryptionRotationService(
                    legacyJdbc, new ObjectMapper());

            StudioEncryptionRotationService.RotationReport report = legacyService.rotate(
                    OLD_SECRET, NEW_SECRET, false);

            assertThat(report.getRotatedValues()).isEqualTo(1);
            assertThat(report.getCandidateRows()).isEqualTo(1);
            assertThat(report.getSkippedTargets())
                    .contains("studio_runtime_endpoint.endpoint_ciphertext",
                            "studio_alert_channel.endpoint_ciphertext",
                            "data_model.technical_metadata",
                            "collection_task_definition.source_bindings_json")
                    .doesNotContain("datasource_definition.technical_metadata");
        } finally {
            legacyDataSource.destroy();
        }
    }

    @Test
    void shouldPageThroughLargeEncryptedResponseValues() {
        for (long id = 1L; id <= 12L; id++) {
            jdbcTemplate.update(
                    "insert into studio_runtime_idempotency (id, response_body_ciphertext) values (?, ?)",
                    id, oldEncryption.encrypt("response-" + id));
        }

        StudioEncryptionRotationService.RotationReport report = service.rotate(
                OLD_SECRET, NEW_SECRET, true);

        assertThat(report.getCandidateRows()).isEqualTo(12);
        assertThat(report.getUpdatedRows()).isEqualTo(12);
        for (long id = 1L; id <= 12L; id++) {
            String ciphertext = jdbcTemplate.queryForObject(
                    "select response_body_ciphertext from studio_runtime_idempotency where id = ?",
                    String.class, id);
            assertThat(newEncryption.decrypt(ciphertext)).isEqualTo("response-" + id);
        }
    }

    private void assertRotatedJson(String table, String column, long id,
                                   String oldCipher, String newCipher) {
        String value = jdbcTemplate.queryForObject(
                "select `" + column + "` from `" + table + "` where id = ?", String.class, id);
        assertThat(value).contains("ENC(" + newCipher + ")").doesNotContain("ENC(" + oldCipher + ")");
    }

    private void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("create table studio_runtime_endpoint (id integer primary key, "
                + "endpoint_ciphertext text, headers_ciphertext text, token_ciphertext text, enabled integer)");
        jdbc.execute("create table studio_alert_channel (id integer primary key, "
                + "endpoint_ciphertext text, headers_ciphertext text, signing_secret_ciphertext text)");
        jdbc.execute("create table studio_runtime_idempotency (id integer primary key, "
                + "response_body_ciphertext text)");
        jdbc.execute("create table dispatch_task (id integer primary key, protected_payload_ciphertext text)");
        jdbc.execute("create table datasource_definition (id integer primary key, technical_metadata text)");
        jdbc.execute("create table data_model (id integer primary key, technical_metadata text)");
        jdbc.execute("create table collection_task_definition (id integer primary key, source_bindings_json text)");
    }
}
