package com.jdragon.studio.test;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class StudioSchemaDriftRegressionTest {

    private static final List<CapabilityExpectation> DEFAULT_CAPABILITIES = Arrays.asList(
            capability("mysql8", "DATABASE", "mysql8", "mysql8", "mysql8"),
            capability("oracle", "DATABASE", "oracle", "oracle", null),
            capability("postgres", "DATABASE", "postgres", "postgresql", "postgresql"),
            capability("dm", "DATABASE", "dm", "dm", "dm"),
            capability("ftp", "FILE_SYSTEM", "ftp", "ftp", "ftp"),
            capability("sftp", "FILE_SYSTEM", "sftp", "sftp", "sftp"),
            capability("minio", "FILE_SYSTEM", "minio", "minio", "minio"),
            capability("kafka", "MESSAGE_QUEUE", "kafka", "kafka", "kafka"),
            capability("rocketmq", "MESSAGE_QUEUE", "rocketmq", "rocketmq", "rocketmq"),
            capability("http", "HTTP_API", "http", "httpreader", "httpwriter"),
            capability("rabbitmq", "MESSAGE_QUEUE", "rabbitmq", null, null),
            capability("odps", "DATABASE", "odps", "odpsreader", "odpswriter"),
            capability("tbds-hdfs", "FILE_SYSTEM", "tbds-hdfs", null, null),
            capability("tbds-hdfs3", "FILE_SYSTEM", "tbds-hdfs3", null, null),
            capability("tbds-hive2", "DATABASE", "tbds-hive2", "tbds-hive2", null),
            capability("tbds-hive3", "DATABASE", "tbds-hive3", null, null),
            capability("influxdb", "DATABASE", "influxdb", null, null),
            capability("influxdbv1", "DATABASE", "influxdbv1", "influxdbv1", "influxdbv1")
    );

    private static final List<RuntimeSchemaExpectation> RUNTIME_SCHEMAS = Arrays.asList(
            runtime("reader", "mysql8"),
            runtime("reader", "dm"),
            runtime("reader", "postgresql"),
            runtime("reader", "tbds-hive2"),
            runtime("reader", "influxdbv1"),
            runtime("reader", "fusion"),
            runtime("reader", "ftp"),
            runtime("reader", "sftp"),
            runtime("reader", "minio"),
            runtime("reader", "http"),
            runtime("reader", "http-soap"),
            runtime("reader", "odps"),
            runtime("writer", "mysql8"),
            runtime("writer", "dm"),
            runtime("writer", "postgresql"),
            runtime("writer", "influxdbv1"),
            runtime("writer", "ftp"),
            runtime("writer", "sftp"),
            runtime("writer", "minio"),
            runtime("writer", "http"),
            runtime("writer", "http-soap"),
            runtime("writer", "odps")
    );

    private static final List<String> TECHNICAL_META_MODEL_CODES = Arrays.asList("source", "table", "field");

    private static final List<String> HTTP_READER_FIELDS = Arrays.asList(
            "contentType", "header", "params", "requestBody", "pageRead", "pageSize");

    private static final List<String> HTTP_WRITER_FIELDS = Arrays.asList(
            "contentType", "header", "params", "requestBody", "payloadMode",
            "dataNodePath", "includeTotal", "totalNodePath", "batchSize",
            "responseStatus.path", "responseStatus.code", "retryTimes", "retryIntervalMs",
            "connectTimeoutMs", "socketTimeoutMs");

    private static final List<String> HTTP_SOAP_READER_FIELDS = Arrays.asList(
            "soapVersion", "soapAction", "contentType", "header", "params", "requestBody",
            "soapFaultFail", "pageRead", "pageSize");

    private static final List<String> HTTP_SOAP_WRITER_FIELDS = Arrays.asList(
            "soapVersion", "soapAction", "contentType", "header", "params", "requestBody",
            "payloadMode", "dataNodePath", "batchSize", "soapFaultFail", "responseStatus.path", "responseStatus.code", "retryTimes",
            "retryIntervalMs", "connectTimeoutMs", "socketTimeoutMs");

    private static final List<String> ODPS_READER_FIELDS = Arrays.asList(
            "readMode", "selectSql", "partitionSpec", "includePartitionColumns", "offset", "maxRows");

    private static final List<String> ODPS_WRITER_FIELDS = Arrays.asList(
            "writeMode", "partitionSpec", "partitionColumns", "batchSize",
            "emptyAsNull", "autoCreatePartition", "preSql", "postSql");

    private static final List<String> DATASOURCE_CONNECTION_STATUS_COLUMNS = Arrays.asList(
            "connection_fingerprint",
            "connection_status", "last_connection_test_at",
            "last_connection_test_message", "last_connection_test_duration_ms",
            "manual_connection_test_timeout_seconds", "scheduled_connection_test_timeout_seconds");

    private static final List<String> DATASOURCE_CONNECTION_HEALTH_COLUMNS = Arrays.asList(
            "datasource_connection_health", "connection_fingerprint", "probe_state",
            "probe_run_id", "probe_lease_until", "failure_count", "next_probe_at");

    private static final List<String> DATASOURCE_CONNECTION_TEST_RECORD_COLUMNS = Arrays.asList(
            "datasource_connection_test_record", "connection_fingerprint", "datasource_id",
            "probe_run_id", "probe_mode", "connection_status", "started_at",
            "ended_at", "duration_ms", "timeout_seconds");

    private static final List<String> DATA_INGESTION_SOURCE_POSITION_COLUMNS = Arrays.asList(
            "data_ingestion_service", "source_positions_json");

    private static final List<String> DATA_INGESTION_SOURCE_BINDING_COLUMNS = Arrays.asList(
            "data_ingestion_service", "source_bindings_json", "source_count", "target_count");

    private static final List<String> HTTP_TABLE_FIELDS = Arrays.asList(
            "physicalName", "description", "protocolMode", "mode", "resultType",
            "soapVersion", "namespaceUri", "operationName", "soapAction",
            "requestRootName", "responseRootName", "wsdlUrl",
            "businessStatusPath", "businessStatusCode", "totalCodePath");

    private static final List<String> HTTP_FIELD_FIELDS = Arrays.asList(
            "name", "cnName", "parentNode", "remarks", "primaryKey", "nullable", "type", "size", "scale");

    private static final List<String> ODPS_FIELD_FIELDS = Arrays.asList(
            "name", "type", "size", "scale", "nullable", "primaryKey",
            "autoIncrement", "remarks", "defaultValue", "partitionColumn");

    @Test
    void defaultDatasourceCapabilitiesShouldStayAlignedAcrossBootstrapMysqlAndSqlite() throws Exception {
        String capabilityBootstrap = readBackendFile("studio-infra/src/main/java/com/jdragon/studio/infra/service/DatasourceTypeCapabilityService.java");
        String mysqlBase = readBackendFile("studio-server/src/main/resources/data-mysql-base.sql");
        String sqliteSchema = readBackendFile("studio-desktop-runtime/src/main/resources/schema-sqlite.sql");

        Set<String> expectedTypeCodes = capabilityTypeCodes();
        assertThat(extractCapabilityTypeCodes(capabilityBootstrap))
                .as("Java default datasource capabilities")
                .containsExactlyElementsOf(expectedTypeCodes);

        for (CapabilityExpectation capability : DEFAULT_CAPABILITIES) {
            assertCapabilityPresent("Java capability bootstrap", capabilityBootstrap, capability, false);
            assertCapabilityPresent("MySQL base SQL", mysqlBase, capability, true);
            assertCapabilityPresent("SQLite schema SQL", sqliteSchema, capability, true);
        }
    }

    @Test
    void runtimeOptionSchemasShouldStayAlignedAcrossBootstrapAndMysqlSql() throws Exception {
        String runtimeBootstrap = readBackendFile("studio-infra/src/main/java/com/jdragon/studio/infra/service/StandardRuntimeOptionSchemaBootstrapService.java");
        String mysqlRuntimeOptions = readBackendFile("studio-server/src/main/resources/data-mysql-runtime-options.sql");

        Set<String> expectedSchemaCodes = runtimeSchemaCodes();
        assertThat(extractRuntimeSchemaCodes(runtimeBootstrap))
                .as("Java runtime option schema bootstrap")
                .containsExactlyInAnyOrderElementsOf(expectedSchemaCodes);
        assertThat(extractRuntimeSchemaCodes(mysqlRuntimeOptions))
                .as("MySQL runtime option schemas")
                .containsExactlyInAnyOrderElementsOf(expectedSchemaCodes);
    }

    @Test
    void technicalMetadataSchemasShouldStayAlignedAcrossBootstrapAndMysqlSql() throws Exception {
        String metadataBootstrap = readBackendFile("studio-infra/src/main/java/com/jdragon/studio/infra/service/MetadataSchemaService.java");
        String technicalFieldBuilder = readBackendFile("studio-infra/src/main/java/com/jdragon/studio/infra/service/TechnicalMetadataFieldBuilder.java");
        String mysqlBuiltin = readBackendFile("studio-server/src/main/resources/data-mysql-builtin.sql");

        assertThat(metadataBootstrap)
                .as("Java technical metadata bootstrap")
                .contains("buildTechnicalMetaModelDraft", "technicalFieldBuilder.buildTechnicalFields");
        assertThat(technicalFieldBuilder)
                .as("Java technical metadata field builder")
                .contains("buildTechnicalFields", "buildSourceFields", "buildTableFields", "buildFieldFields");
        assertThat(extractTechnicalSchemaCodes(mysqlBuiltin))
                .as("MySQL builtin technical metadata schemas")
                .containsExactlyInAnyOrderElementsOf(technicalSchemaCodes());
    }

    @Test
    void httpCapabilityShouldStayAlignedAcrossJavaMysqlSqliteAndDeltaScripts() throws Exception {
        String capabilityBootstrap = readBackendFile("studio-infra/src/main/java/com/jdragon/studio/infra/service/DatasourceTypeCapabilityService.java");
        String mysqlBase = readBackendFile("studio-server/src/main/resources/data-mysql-base.sql");
        String sqliteSchema = readBackendFile("studio-desktop-runtime/src/main/resources/schema-sqlite.sql");
        String httpDelta = readBackendFile("studio-server/src/main/resources/update/20260524/20260508-to-20260524-http-reader-writer-delta.sql");

        assertHttpCapability("Java capability bootstrap", capabilityBootstrap);
        assertHttpCapability("MySQL base SQL", mysqlBase);
        assertHttpCapability("SQLite schema SQL", sqliteSchema);
        assertHttpCapability("HTTP delta SQL", httpDelta);
    }

    @Test
    void httpRuntimeOptionSchemasShouldStayAlignedAcrossBootstrapMysqlAndDeltaScripts() throws Exception {
        String runtimeBootstrap = readBackendFile("studio-infra/src/main/java/com/jdragon/studio/infra/service/StandardRuntimeOptionSchemaBootstrapService.java");
        String mysqlRuntimeOptions = readBackendFile("studio-server/src/main/resources/data-mysql-runtime-options.sql");
        String httpDelta = readBackendFile("studio-server/src/main/resources/update/20260524/20260508-to-20260524-http-reader-writer-delta.sql")
                + readBackendFile("studio-server/src/main/resources/update/20260609/20260609-http-webservice-runtime-delta.sql");

        assertThat(runtimeBootstrap).contains("buildHttpReaderFields", "buildHttpWriterFields");
        assertThat(runtimeBootstrap).contains("buildHttpSoapReaderFields", "buildHttpSoapWriterFields");
        assertThat(mysqlRuntimeOptions).contains("runtime:reader:http", "runtime:writer:http", "runtime:reader:http-soap", "runtime:writer:http-soap");
        assertThat(httpDelta).contains("runtime:reader:http", "runtime:writer:http");

        assertFieldsPresent("Java HTTP reader runtime fields", runtimeBootstrap, HTTP_READER_FIELDS);
        assertFieldsPresent("MySQL HTTP reader runtime fields", mysqlRuntimeOptions, HTTP_READER_FIELDS);
        assertFieldsPresent("Delta HTTP reader runtime fields", httpDelta, HTTP_READER_FIELDS);
        assertFieldsPresent("Java HTTP writer runtime fields", runtimeBootstrap, HTTP_WRITER_FIELDS);
        assertFieldsPresent("MySQL HTTP writer runtime fields", mysqlRuntimeOptions, HTTP_WRITER_FIELDS);
        assertFieldsPresent("Delta HTTP writer runtime fields", httpDelta, HTTP_WRITER_FIELDS);
        assertFieldsPresent("Java HTTP SOAP reader runtime fields", runtimeBootstrap, HTTP_SOAP_READER_FIELDS);
        assertFieldsPresent("MySQL HTTP SOAP reader runtime fields", mysqlRuntimeOptions, HTTP_SOAP_READER_FIELDS);
        assertFieldsPresent("Java HTTP SOAP writer runtime fields", runtimeBootstrap, HTTP_SOAP_WRITER_FIELDS);
        assertFieldsPresent("MySQL HTTP SOAP writer runtime fields", mysqlRuntimeOptions, HTTP_SOAP_WRITER_FIELDS);
    }

    @Test
    void odpsCapabilityAndRuntimeSchemasShouldStayAlignedAcrossBootstrapMysqlSqliteAndDeltaScripts() throws Exception {
        String capabilityBootstrap = readBackendFile("studio-infra/src/main/java/com/jdragon/studio/infra/service/DatasourceTypeCapabilityService.java");
        String mysqlBase = readBackendFile("studio-server/src/main/resources/data-mysql-base.sql");
        String sqliteSchema = readBackendFile("studio-desktop-runtime/src/main/resources/schema-sqlite.sql");
        String runtimeBootstrap = readBackendFile("studio-infra/src/main/java/com/jdragon/studio/infra/service/StandardRuntimeOptionSchemaBootstrapService.java");
        String mysqlRuntimeOptions = readBackendFile("studio-server/src/main/resources/data-mysql-runtime-options.sql");
        String technicalFieldBuilder = readBackendFile("studio-infra/src/main/java/com/jdragon/studio/infra/service/TechnicalMetadataFieldBuilder.java");
        String mysqlBuiltin = readBackendFile("studio-server/src/main/resources/data-mysql-builtin.sql");
        String odpsDelta = readBackendFile("studio-server/src/main/resources/update/20260605/20260605-odps-studio-integration-delta.sql");
        String odpsFieldDelta = readBackendFile("studio-server/src/main/resources/update/20260606/20260606-odps-field-meta-schema-delta.sql");

        CapabilityExpectation odps = capability("odps", "DATABASE", "odps", "odpsreader", "odpswriter");
        assertCapabilityPresent("Java ODPS capability", capabilityBootstrap, odps, false);
        assertCapabilityPresent("MySQL ODPS capability", mysqlBase, odps, true);
        assertCapabilityPresent("SQLite ODPS capability", sqliteSchema, odps, true);
        assertCapabilityPresent("Delta ODPS capability", odpsDelta, odps, true);

        assertThat(runtimeBootstrap).contains("buildOdpsReaderFields", "buildOdpsWriterFields");
        assertThat(mysqlRuntimeOptions).contains("runtime:reader:odps", "runtime:writer:odps");
        assertThat(odpsDelta).contains("runtime:reader:odps", "runtime:writer:odps");

        assertFieldsPresent("Java ODPS reader runtime fields", runtimeBootstrap, ODPS_READER_FIELDS);
        assertFieldsPresent("MySQL ODPS reader runtime fields", mysqlRuntimeOptions, ODPS_READER_FIELDS);
        assertFieldsPresent("Delta ODPS reader runtime fields", odpsDelta, ODPS_READER_FIELDS);
        assertFieldsPresent("Java ODPS writer runtime fields", runtimeBootstrap, ODPS_WRITER_FIELDS);
        assertFieldsPresent("MySQL ODPS writer runtime fields", mysqlRuntimeOptions, ODPS_WRITER_FIELDS);
        assertFieldsPresent("Delta ODPS writer runtime fields", odpsDelta, ODPS_WRITER_FIELDS);

        assertFieldsPresent("Java ODPS technical field metadata", technicalFieldBuilder, ODPS_FIELD_FIELDS);
        assertFieldsPresent("MySQL ODPS technical field metadata", mysqlBuiltin, ODPS_FIELD_FIELDS);
        assertFieldsPresent("Delta ODPS technical field metadata", odpsDelta, Arrays.asList("partitionColumn"));
        assertFieldsPresent("Repair delta ODPS technical field metadata", odpsFieldDelta, ODPS_FIELD_FIELDS);
    }

    @Test
    void httpTechnicalMetadataSchemasShouldStayAlignedAcrossBootstrapMysqlAndDeltaScripts() throws Exception {
        String technicalFieldBuilder = readBackendFile("studio-infra/src/main/java/com/jdragon/studio/infra/service/TechnicalMetadataFieldBuilder.java");
        String mysqlBuiltin = readBackendFile("studio-server/src/main/resources/data-mysql-builtin.sql");
        String httpDelta = readBackendFile("studio-server/src/main/resources/update/20260524/20260508-to-20260524-http-reader-writer-delta.sql")
                + readBackendFile("studio-server/src/main/resources/update/20260609/20260609-http-webservice-runtime-delta.sql");

        assertThat(technicalFieldBuilder).contains("http", "businessStatusPath", "parentNode");
        assertThat(mysqlBuiltin).contains("technical:http:source", "technical:http:table", "technical:http:field");
        assertThat(httpDelta).contains("technical:http:source", "technical:http:table", "technical:http:field");

        assertFieldsPresent("Java HTTP table metadata fields", technicalFieldBuilder, HTTP_TABLE_FIELDS);
        assertFieldsPresent("MySQL HTTP table metadata fields", mysqlBuiltin, HTTP_TABLE_FIELDS);
        assertFieldsPresent("Delta HTTP table metadata fields", httpDelta, HTTP_TABLE_FIELDS);
        assertFieldsPresent("Java HTTP field metadata fields", technicalFieldBuilder, HTTP_FIELD_FIELDS);
        assertFieldsPresent("MySQL HTTP field metadata fields", mysqlBuiltin, HTTP_FIELD_FIELDS);
        assertFieldsPresent("Delta HTTP field metadata fields", httpDelta, HTTP_FIELD_FIELDS);
    }

    @Test
    void datasourceConnectionStatusColumnsShouldStayAlignedAcrossMysqlSqliteAndDeltaScripts() throws Exception {
        String mysqlSchema = readBackendFile("studio-server/src/main/resources/schema-mysql.sql");
        String sqliteSchema = readBackendFile("studio-desktop-runtime/src/main/resources/schema-sqlite.sql");
        String delta = readBackendFile("studio-server/src/main/resources/update/20260617/20260617-datasource-connection-status-delta.sql");

        assertFieldsPresent("MySQL datasource connection status columns", mysqlSchema, DATASOURCE_CONNECTION_STATUS_COLUMNS);
        assertFieldsPresent("SQLite datasource connection status columns", sqliteSchema, DATASOURCE_CONNECTION_STATUS_COLUMNS);
        assertFieldsPresent("Delta datasource connection status columns", delta, DATASOURCE_CONNECTION_STATUS_COLUMNS);
        assertFieldsPresent("MySQL datasource connection health table", mysqlSchema, DATASOURCE_CONNECTION_HEALTH_COLUMNS);
        assertFieldsPresent("SQLite datasource connection health table", sqliteSchema, DATASOURCE_CONNECTION_HEALTH_COLUMNS);
        assertFieldsPresent("Delta datasource connection health table", delta, DATASOURCE_CONNECTION_HEALTH_COLUMNS);
        assertFieldsPresent("MySQL datasource connection test record table", mysqlSchema, DATASOURCE_CONNECTION_TEST_RECORD_COLUMNS);
        assertFieldsPresent("SQLite datasource connection test record table", sqliteSchema, DATASOURCE_CONNECTION_TEST_RECORD_COLUMNS);
        assertFieldsPresent("Delta datasource connection test record table", delta, DATASOURCE_CONNECTION_TEST_RECORD_COLUMNS);
    }

    @Test
    void activeSubscriptionUniquenessShouldStayAlignedAcrossSchemaAndUpgrade() throws Exception {
        String mysqlSchema = readBackendFile("studio-server/src/main/resources/schema-mysql.sql");
        String sqliteSchema = readBackendFile("studio-desktop-runtime/src/main/resources/schema-sqlite.sql");
        String upgradeService = readBackendFile("studio-infra/src/main/java/com/jdragon/studio/infra/service/StudioSchemaUpgradeService.java");

        assertFieldsPresent("MySQL active subscription generated column", mysqlSchema,
                Arrays.asList("active_subscription_name", "uk_data_service_sub_active_name",
                        "uk_data_ingestion_sub_active_name", "uk_protocol_conversion_sub_active_name"));
        assertFieldsPresent("SQLite active subscription partial indexes", sqliteSchema,
                Arrays.asList("uk_data_ingestion_sub_active_name", "uk_protocol_conversion_sub_active_name",
                        "where enabled = 1"));
        assertFieldsPresent("Upgrade active subscription uniqueness", upgradeService,
                Arrays.asList("ensureActiveSubscriptionUniquenessMysql", "ensureActiveSubscriptionUniquenessSqlite",
                        "uk_data_service_sub_active_name", "uk_data_ingestion_sub_active_name",
                        "uk_protocol_conversion_sub_active_name"));
    }

    @Test
    void dataIngestionSourcePositionSummaryColumnShouldStayAlignedAcrossSchemaAndUpgrade() throws Exception {
        String mysqlSchema = readBackendFile("studio-server/src/main/resources/schema-mysql.sql");
        String sqliteSchema = readBackendFile("studio-desktop-runtime/src/main/resources/schema-sqlite.sql");
        String upgradeService = readBackendFile("studio-infra/src/main/java/com/jdragon/studio/infra/service/StudioSchemaUpgradeService.java");
        String delta = readBackendFile("studio-server/src/main/resources/update/20260629/20260629-data-ingestion-source-positions-delta.sql");

        assertFieldsPresent("MySQL data ingestion source positions summary", mysqlSchema, DATA_INGESTION_SOURCE_POSITION_COLUMNS);
        assertFieldsPresent("SQLite data ingestion source positions summary", sqliteSchema, DATA_INGESTION_SOURCE_POSITION_COLUMNS);
        assertFieldsPresent("Upgrade data ingestion source positions summary", upgradeService,
                Arrays.asList("source_positions_json", "backfillDataIngestionSourcePositions"));
        assertFieldsPresent("Delta data ingestion source positions summary", delta, DATA_INGESTION_SOURCE_POSITION_COLUMNS);
    }

    @Test
    void dataIngestionSourceBindingsShouldStayAlignedAcrossSchemaUpgradeAndDeltaScripts() throws Exception {
        String mysqlSchema = readBackendFile("studio-server/src/main/resources/schema-mysql.sql");
        String sqliteSchema = readBackendFile("studio-desktop-runtime/src/main/resources/schema-sqlite.sql");
        String upgradeService = readBackendFile("studio-infra/src/main/java/com/jdragon/studio/infra/service/StudioSchemaUpgradeService.java");
        String delta = readBackendFile("studio-server/src/main/resources/update/20260630/20260630-data-ingestion-source-bindings-delta.sql");

        assertFieldsPresent("MySQL data ingestion source bindings", mysqlSchema, DATA_INGESTION_SOURCE_BINDING_COLUMNS);
        assertFieldsPresent("SQLite data ingestion source bindings", sqliteSchema, DATA_INGESTION_SOURCE_BINDING_COLUMNS);
        assertFieldsPresent("Upgrade data ingestion source bindings", upgradeService,
                Arrays.asList("source_bindings_json", "source_count", "target_count",
                        "backfillDataIngestionSourceBindings", "legacyDataIngestionSourceBindings"));
        assertFieldsPresent("Delta data ingestion source bindings", delta, DATA_INGESTION_SOURCE_BINDING_COLUMNS);
    }

    private void assertHttpCapability(String label, String content) {
        assertThat(content)
                .as(label)
                .contains("http", "HTTP_API", "httpreader", "httpwriter", "HTTP 接口数据源");
    }

    private void assertFieldsPresent(String label, String content, List<String> fields) {
        assertThat(content)
                .as(label)
                .contains(fields.toArray(new String[0]));
    }

    private void assertCapabilityPresent(String label,
                                         String content,
                                         CapabilityExpectation capability,
                                         boolean sqlFormat) {
        assertThat(content)
                .as(label + " typeCode=" + capability.typeCode)
                .contains(sqlFormat ? "'" + capability.typeCode + "'" : "\"" + capability.typeCode + "\"")
                .contains(sqlFormat ? "'" + capability.sourceCategory + "'" : capability.sourceCategory)
                .contains(sqlFormat ? "'" + capability.sourcePlugin + "'" : "\"" + capability.sourcePlugin + "\"");
        for (String readerPlugin : capability.readerPlugins) {
            assertThat(content)
                    .as(label + " reader plugin for " + capability.typeCode)
                    .contains(sqlFormat ? "\"" + readerPlugin + "\"" : "\"" + readerPlugin + "\"");
        }
        for (String writerPlugin : capability.writerPlugins) {
            assertThat(content)
                    .as(label + " writer plugin for " + capability.typeCode)
                    .contains(sqlFormat ? "\"" + writerPlugin + "\"" : "\"" + writerPlugin + "\"");
        }
    }

    private Set<String> capabilityTypeCodes() {
        Set<String> result = new LinkedHashSet<String>();
        for (CapabilityExpectation capability : DEFAULT_CAPABILITIES) {
            result.add(capability.typeCode);
        }
        return result;
    }

    private Set<String> runtimeSchemaCodes() {
        Set<String> result = new LinkedHashSet<String>();
        for (RuntimeSchemaExpectation schema : RUNTIME_SCHEMAS) {
            result.add(schema.role + ":" + schema.pluginType);
        }
        return result;
    }

    private Set<String> technicalSchemaCodes() {
        Set<String> result = new LinkedHashSet<String>();
        for (CapabilityExpectation capability : DEFAULT_CAPABILITIES) {
            for (String metaModelCode : TECHNICAL_META_MODEL_CODES) {
                result.add("technical:" + capability.typeCode + ":" + metaModelCode);
            }
        }
        return result;
    }

    private Set<String> extractCapabilityTypeCodes(String content) {
        return extract(content, Pattern.compile("capability\\(\"([a-z0-9\\-]+)\""));
    }

    private Set<String> extractRuntimeSchemaCodes(String content) {
        Set<String> fromJava = extract(content, Pattern.compile("ensureRuntimeOptionSchema\\(\"(reader|writer)\",\\s*\"([a-z0-9\\-]+)\""));
        if (!fromJava.isEmpty()) {
            return fromJava;
        }
        return extract(content, Pattern.compile("runtime:(reader|writer):([a-z0-9\\-]+)"));
    }

    private Set<String> extractTechnicalSchemaCodes(String content) {
        return extract(content, Pattern.compile("technical:([a-z0-9\\-]+):(source|table|field)"), true);
    }

    private Set<String> extract(String content, Pattern pattern) {
        return extract(content, pattern, false);
    }

    private Set<String> extract(String content, Pattern pattern, boolean technicalSchema) {
        Set<String> result = new LinkedHashSet<String>();
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            if (matcher.groupCount() == 1) {
                result.add(matcher.group(1));
            } else if (technicalSchema) {
                result.add("technical:" + matcher.group(1) + ":" + matcher.group(2));
            } else {
                result.add(matcher.group(1) + ":" + matcher.group(2));
            }
        }
        return result;
    }

    private static CapabilityExpectation capability(String typeCode,
                                                    String sourceCategory,
                                                    String sourcePlugin,
                                                    String readerPlugin,
                                                    String writerPlugin) {
        List<String> readerPlugins = new ArrayList<String>();
        if (readerPlugin != null) {
            readerPlugins.add(readerPlugin);
        }
        List<String> writerPlugins = new ArrayList<String>();
        if (writerPlugin != null) {
            writerPlugins.add(writerPlugin);
        }
        return new CapabilityExpectation(typeCode, sourceCategory, sourcePlugin, readerPlugins, writerPlugins);
    }

    private static RuntimeSchemaExpectation runtime(String role, String pluginType) {
        return new RuntimeSchemaExpectation(role, pluginType);
    }

    private String readBackendFile(String relativePath) throws IOException {
        Path path = resolveBackendFile(relativePath);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private Path resolveBackendFile(String relativePath) {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path direct = current.resolve(relativePath);
            if (Files.exists(direct)) {
                return direct;
            }
            Path underBackend = current.resolve("backend").resolve(relativePath);
            if (Files.exists(underBackend)) {
                return underBackend;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate backend file: " + relativePath);
    }

    private static final class CapabilityExpectation {
        private final String typeCode;
        private final String sourceCategory;
        private final String sourcePlugin;
        private final List<String> readerPlugins;
        private final List<String> writerPlugins;

        private CapabilityExpectation(String typeCode,
                                      String sourceCategory,
                                      String sourcePlugin,
                                      List<String> readerPlugins,
                                      List<String> writerPlugins) {
            this.typeCode = typeCode;
            this.sourceCategory = sourceCategory;
            this.sourcePlugin = sourcePlugin;
            this.readerPlugins = readerPlugins;
            this.writerPlugins = writerPlugins;
        }
    }

    private static final class RuntimeSchemaExpectation {
        private final String role;
        private final String pluginType;

        private RuntimeSchemaExpectation(String role, String pluginType) {
            this.role = role;
            this.pluginType = pluginType;
        }
    }
}
