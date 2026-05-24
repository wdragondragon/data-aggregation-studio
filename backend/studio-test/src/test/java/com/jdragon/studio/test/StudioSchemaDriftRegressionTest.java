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
            capability("odps", "DATABASE", "odps", null, null),
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
            runtime("writer", "mysql8"),
            runtime("writer", "dm"),
            runtime("writer", "postgresql"),
            runtime("writer", "influxdbv1"),
            runtime("writer", "ftp"),
            runtime("writer", "sftp"),
            runtime("writer", "minio"),
            runtime("writer", "http")
    );

    private static final List<String> TECHNICAL_META_MODEL_CODES = Arrays.asList("source", "table", "field");

    private static final List<String> HTTP_READER_FIELDS = Arrays.asList(
            "contentType", "header", "params", "requestBody", "pageRead", "pageSize");

    private static final List<String> HTTP_WRITER_FIELDS = Arrays.asList(
            "contentType", "header", "params", "requestBody", "payloadMode",
            "dataNodePath", "includeTotal", "totalNodePath", "batchSize",
            "responseStatus.path", "responseStatus.code", "retryTimes", "retryIntervalMs",
            "connectTimeoutMs", "socketTimeoutMs");

    private static final List<String> HTTP_TABLE_FIELDS = Arrays.asList(
            "physicalName", "description", "mode", "resultType",
            "businessStatusPath", "businessStatusCode", "totalCodePath");

    private static final List<String> HTTP_FIELD_FIELDS = Arrays.asList(
            "name", "cnName", "parentNode", "remarks", "primaryKey", "nullable", "type", "size", "scale");

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
        String httpDelta = readBackendFile("studio-server/src/main/resources/update/20260524/20260508-to-20260524-http-reader-writer-delta.sql");

        assertThat(runtimeBootstrap).contains("buildHttpReaderFields", "buildHttpWriterFields");
        assertThat(mysqlRuntimeOptions).contains("runtime:reader:http", "runtime:writer:http");
        assertThat(httpDelta).contains("runtime:reader:http", "runtime:writer:http");

        assertFieldsPresent("Java HTTP reader runtime fields", runtimeBootstrap, HTTP_READER_FIELDS);
        assertFieldsPresent("MySQL HTTP reader runtime fields", mysqlRuntimeOptions, HTTP_READER_FIELDS);
        assertFieldsPresent("Delta HTTP reader runtime fields", httpDelta, HTTP_READER_FIELDS);
        assertFieldsPresent("Java HTTP writer runtime fields", runtimeBootstrap, HTTP_WRITER_FIELDS);
        assertFieldsPresent("MySQL HTTP writer runtime fields", mysqlRuntimeOptions, HTTP_WRITER_FIELDS);
        assertFieldsPresent("Delta HTTP writer runtime fields", httpDelta, HTTP_WRITER_FIELDS);
    }

    @Test
    void httpTechnicalMetadataSchemasShouldStayAlignedAcrossBootstrapMysqlAndDeltaScripts() throws Exception {
        String technicalFieldBuilder = readBackendFile("studio-infra/src/main/java/com/jdragon/studio/infra/service/TechnicalMetadataFieldBuilder.java");
        String mysqlBuiltin = readBackendFile("studio-server/src/main/resources/data-mysql-builtin.sql");
        String httpDelta = readBackendFile("studio-server/src/main/resources/update/20260524/20260508-to-20260524-http-reader-writer-delta.sql");

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
