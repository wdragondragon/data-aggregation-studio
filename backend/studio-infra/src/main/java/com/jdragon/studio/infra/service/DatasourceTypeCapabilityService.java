package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DatasourceTypeCapabilityView;
import com.jdragon.studio.infra.entity.DatasourceTypeCapabilityEntity;
import com.jdragon.studio.infra.mapper.DatasourceTypeCapabilityMapper;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class DatasourceTypeCapabilityService {

    private static final String CATEGORY_DATABASE = "DATABASE";
    private static final String CATEGORY_FILE_SYSTEM = "FILE_SYSTEM";
    private static final String CATEGORY_MESSAGE_QUEUE = "MESSAGE_QUEUE";
    private static final String CATEGORY_HTTP_API = "HTTP_API";

    private static final List<DefaultCapability> DEFAULT_CAPABILITIES = Collections.unmodifiableList(Arrays.asList(
            capability("mysql8", "MySQL 8", CATEGORY_DATABASE, true, true, true, true, true, "mysql8", list("mysql8"), list("mysql8"), 10, "MySQL 数据库"),
            capability("oracle", "Oracle", CATEGORY_DATABASE, true, true, false, true, true, "oracle", list("oracle"), list(), 20, "Oracle 数据库"),
            capability("postgres", "PostgreSQL", CATEGORY_DATABASE, true, true, true, true, true, "postgres", list("postgresql"), list("postgresql"), 30, "PostgreSQL 数据库"),
            capability("dm", "达梦数据库", CATEGORY_DATABASE, true, true, true, true, true, "dm", list("dm"), list("dm"), 40, "达梦数据库"),
            capability("local", "Local File", CATEGORY_FILE_SYSTEM, true, false, false, true, false, "local", list(), list(), 45, "本地文件系统"),
            capability("ftp", "FTP", CATEGORY_FILE_SYSTEM, true, true, true, true, false, "ftp", list("ftp"), list("ftp"), 50, "FTP 文件数据源"),
            capability("sftp", "SFTP", CATEGORY_FILE_SYSTEM, true, true, true, true, false, "sftp", list("sftp"), list("sftp"), 60, "SFTP 文件数据源"),
            capability("minio", "MinIO", CATEGORY_FILE_SYSTEM, true, true, true, true, false, "minio", list("minio"), list("minio"), 70, "MinIO / OSS 对象存储"),
            capability("oss", "Aliyun OSS", CATEGORY_FILE_SYSTEM, true, false, false, true, false, "oss", list(), list(), 75, "阿里云 OSS 对象存储"),
            capability("kafka", "Kafka", CATEGORY_MESSAGE_QUEUE, true, true, true, true, false, "kafka", list("kafka"), list("kafka"), 80, "Kafka 消息队列"),
            capability("rocketmq", "RocketMQ", CATEGORY_MESSAGE_QUEUE, true, true, true, true, false, "rocketmq", list("rocketmq"), list("rocketmq"), 90, "RocketMQ 消息队列"),
            capability("http", "HTTP", CATEGORY_HTTP_API, true, true, true, true, false, "http", list("httpreader"), list("httpwriter"), 95, "HTTP 接口数据源"),
            capability("rabbitmq", "RabbitMQ", CATEGORY_MESSAGE_QUEUE, true, false, false, true, false, "rabbitmq", list(), list(), 100, "RabbitMQ 消息队列"),
            capability("odps", "ODPS", CATEGORY_DATABASE, true, true, true, true, true, "odps", list("odpsreader"), list("odpswriter"), 110, "ODPS / MaxCompute 数据源"),
            capability("tbds-hdfs", "TBDS HDFS", CATEGORY_FILE_SYSTEM, true, false, false, true, false, "tbds-hdfs", list(), list(), 120, "TBDS HDFS 文件系统"),
            capability("tbds-hdfs3", "TBDS HDFS3", CATEGORY_FILE_SYSTEM, true, false, false, true, false, "tbds-hdfs3", list(), list(), 130, "TBDS HDFS3 文件系统"),
            capability("tbds-hive2", "TBDS Hive2", CATEGORY_DATABASE, true, true, false, true, true, "tbds-hive2", list("tbds-hive2"), list(), 140, "TBDS Hive2 数据源"),
            capability("tbds-hive3", "TBDS Hive3", CATEGORY_DATABASE, true, false, false, true, true, "tbds-hive3", list(), list(), 150, "TBDS Hive3 数据源"),
            capability("influxdb", "InfluxDB", CATEGORY_DATABASE, true, false, false, true, false, "influxdb", list(), list(), 160, "InfluxDB 数据源"),
            capability("influxdbv1", "InfluxDB v1", CATEGORY_DATABASE, true, true, true, true, false, "influxdbv1", list("influxdbv1"), list("influxdbv1"), 170, "InfluxDB v1 数据源")
    ));

    private final DatasourceTypeCapabilityMapper mapper;
    private final JdbcTemplate jdbcTemplate;

    public DatasourceTypeCapabilityService(DatasourceTypeCapabilityMapper mapper, JdbcTemplate jdbcTemplate) {
        this.mapper = mapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void bootstrapDefaults() {
        for (DefaultCapability capability : DEFAULT_CAPABILITIES) {
            DatasourceTypeCapabilityEntity existing = mapper.selectOne(baseCapabilityQuery()
                    .eq("tenant_id", "default")
                    .eq("type_code", capability.typeCode)
                    .last("limit 1"));
            if (existing != null) {
                continue;
            }
            DatasourceTypeCapabilityEntity entity = new DatasourceTypeCapabilityEntity();
            entity.setTenantId("default");
            entity.setTypeCode(capability.typeCode);
            entity.setTypeName(capability.typeName);
            entity.setEnabled(flag(capability.enabled));
            entity.setReadable(flag(capability.readable));
            entity.setWritable(flag(capability.writable));
            entity.setExecutable(flag(capability.executable));
            entity.setSqlExecutable(flag(capability.sqlExecutable));
            if (hasSourceCategoryColumn()) {
                entity.setSourceCategory(capability.sourceCategory);
            }
            entity.setSourcePlugin(capability.sourcePlugin);
            entity.setReaderPluginsJson(new ArrayList<String>(capability.readerPlugins));
            entity.setWriterPluginsJson(new ArrayList<String>(capability.writerPlugins));
            if (hasRuntimeCapabilitiesColumn()) {
                entity.setRuntimeCapabilitiesJson(runtimeCapabilitiesFor(capability.typeCode));
            }
            entity.setSortOrder(capability.sortOrder);
            entity.setDescription(capability.description);
            mapper.insert(entity);
        }
    }

    @Transactional
    public void syncStandardRuntimePluginCapabilities() {
        syncDefaultCapability("local");
        syncDefaultCapability("postgres");
        syncDefaultCapability("ftp");
        syncDefaultCapability("sftp");
        syncDefaultCapability("minio");
        syncDefaultCapability("oss");
        syncDefaultCapability("http");
        syncDefaultCapability("odps");
    }

    public List<DatasourceTypeCapabilityView> listEnabled() {
        List<DatasourceTypeCapabilityEntity> entities = mapper.selectList(baseCapabilityQuery()
                .eq("tenant_id", "default")
                .eq("enabled", Integer.valueOf(1))
                .orderByAsc("sort_order")
                .orderByAsc("type_code"));
        List<DatasourceTypeCapabilityView> result = new ArrayList<DatasourceTypeCapabilityView>();
        for (DatasourceTypeCapabilityEntity entity : entities) {
            result.add(toView(entity));
        }
        return result;
    }

    public List<Map<String, Object>> sourceCapabilities() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (DatasourceTypeCapabilityView view : listEnabled()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("typeCode", view.getTypeCode());
            row.put("typeName", view.getTypeName());
            row.put("sourceCategory", view.getSourceCategory());
            row.put("sourcePlugin", view.getSourcePlugin());
            row.put("readable", Boolean.TRUE.equals(view.getReadable()));
            row.put("writable", Boolean.TRUE.equals(view.getWritable()));
            row.put("executable", Boolean.TRUE.equals(view.getExecutable()));
            row.put("sqlExecutable", Boolean.TRUE.equals(view.getSqlExecutable()));
            row.put("readerPlugins", view.getReaderPlugins());
            row.put("writerPlugins", view.getWriterPlugins());
            row.put("runtimeCapabilities", view.getRuntimeCapabilities());
            rows.add(row);
        }
        return rows;
    }

    public List<String> executableSourceTypes() {
        return typesByFlag("readable");
    }

    public List<String> executableTargetTypes() {
        return typesByFlag("writable");
    }

    public List<String> executableDatasourceTypes() {
        return typesByFlag("executable");
    }

    public List<String> sqlExecutableTypes() {
        return typesByFlag("sqlExecutable");
    }

    public List<String> sourceTypes() {
        List<String> result = new ArrayList<String>();
        for (DatasourceTypeCapabilityView view : listEnabled()) {
            result.add(view.getTypeCode());
        }
        return result;
    }

    public boolean isEnabled(String typeCode) {
        DatasourceTypeCapabilityEntity entity = findEnabledEntity(typeCode);
        return entity != null;
    }

    public boolean isReadable(String typeCode) {
        DatasourceTypeCapabilityEntity entity = findEnabledEntity(typeCode);
        if (entity == null) {
            entity = findEnabledEntityByRuntimePlugin(typeCode, "reader");
        }
        return entity != null && enabled(entity.getReadable());
    }

    public boolean isWritable(String typeCode) {
        DatasourceTypeCapabilityEntity entity = findEnabledEntity(typeCode);
        if (entity == null) {
            entity = findEnabledEntityByRuntimePlugin(typeCode, "writer");
        }
        return entity != null && enabled(entity.getWritable());
    }

    public boolean isExecutable(String typeCode) {
        DatasourceTypeCapabilityEntity entity = findEnabledEntity(typeCode);
        return entity != null && enabled(entity.getExecutable());
    }

    public boolean isSqlExecutable(String typeCode) {
        DatasourceTypeCapabilityEntity entity = findEnabledEntity(typeCode);
        return entity != null && enabled(entity.getSqlExecutable());
    }

    public void ensureEnabled(String typeCode) {
        if (!isEnabled(typeCode)) {
            throw unsupported("enabled", typeCode);
        }
    }

    public void ensureReadable(String typeCode) {
        if (!isReadable(typeCode)) {
            throw unsupported("readable", typeCode);
        }
    }

    public void ensureWritable(String typeCode) {
        if (!isWritable(typeCode)) {
            throw unsupported("writable", typeCode);
        }
    }

    public void ensureExecutable(String typeCode) {
        if (!isExecutable(typeCode)) {
            throw unsupported("managed", typeCode);
        }
    }

    public void ensureSqlExecutable(String typeCode) {
        if (!isSqlExecutable(typeCode)) {
            throw unsupported("SQL executable", typeCode);
        }
    }

    private List<String> typesByFlag(String flagName) {
        Set<String> types = new LinkedHashSet<String>();
        for (DatasourceTypeCapabilityView view : listEnabled()) {
            boolean accepted;
            if ("readable".equals(flagName)) {
                accepted = Boolean.TRUE.equals(view.getReadable());
            } else if ("writable".equals(flagName)) {
                accepted = Boolean.TRUE.equals(view.getWritable());
            } else if ("sqlExecutable".equals(flagName)) {
                accepted = Boolean.TRUE.equals(view.getSqlExecutable());
            } else {
                accepted = Boolean.TRUE.equals(view.getExecutable());
            }
            if (accepted && view.getTypeCode() != null) {
                types.add(view.getTypeCode());
            }
        }
        return new ArrayList<String>(types);
    }

    public boolean hasRuntimeCapability(String typeCode, String capability) {
        DatasourceTypeCapabilityEntity entity = findEnabledEntity(typeCode);
        if (entity == null || capability == null || capability.isBlank()) {
            return false;
        }
        Object binary = safeMap(entity.getRuntimeCapabilitiesJson()).get("binaryFile");
        Object value = safeMap(binary).get(capability);
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    public void ensureRuntimeCapability(String typeCode, String capability) {
        if (!hasRuntimeCapability(typeCode, capability)) {
            throw unsupported("binaryFile." + capability, typeCode);
        }
    }

    public Set<String> typesWithRuntimeCapability(String capability, boolean includeAliases) {
        Set<String> result = new LinkedHashSet<String>();
        for (DatasourceTypeCapabilityView view : listEnabled()) {
            Object binary = safeMap(view.getRuntimeCapabilities()).get("binaryFile");
            Object value = safeMap(binary).get(capability);
            if (Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value))) {
                result.add(view.getTypeCode());
            }
        }
        if (includeAliases && result.contains("local")) {
            result.add("file");
            result.add("local_file");
        }
        if (includeAliases && result.contains("oss")) {
            result.add("aliyun");
            result.add("aliyun_oss");
            result.add("aliyun-oss");
        }
        return result;
    }

    private void syncDefaultCapability(String typeCode) {
        DefaultCapability capability = findDefaultCapability(typeCode);
        if (capability == null) {
            return;
        }
        DatasourceTypeCapabilityEntity existing = mapper.selectOne(baseCapabilityQuery()
                .eq("tenant_id", "default")
                .eq("type_code", capability.typeCode)
                .last("limit 1"));
        if (existing == null) {
            bootstrapDefaults();
            return;
        }
        boolean changed = false;
        if (!sameStringList(existing.getReaderPluginsJson(), capability.readerPlugins)) {
            existing.setReaderPluginsJson(new ArrayList<String>(capability.readerPlugins));
            changed = true;
        }
        if (!sameStringList(existing.getWriterPluginsJson(), capability.writerPlugins)) {
            existing.setWriterPluginsJson(new ArrayList<String>(capability.writerPlugins));
            changed = true;
        }
        if (!Integer.valueOf(flag(capability.readable)).equals(existing.getReadable())) {
            existing.setReadable(flag(capability.readable));
            changed = true;
        }
        if (!Integer.valueOf(flag(capability.writable)).equals(existing.getWritable())) {
            existing.setWritable(flag(capability.writable));
            changed = true;
        }
        if (hasSourceCategoryColumn() && !capability.sourceCategory.equalsIgnoreCase(resolveSourceCategory(existing.getTypeCode(), existing.getSourceCategory()))) {
            existing.setSourceCategory(capability.sourceCategory);
            changed = true;
        }
        Map<String, Object> runtimeCapabilities = runtimeCapabilitiesFor(capability.typeCode);
        if (hasRuntimeCapabilitiesColumn()
                && !Objects.equals(safeMap(existing.getRuntimeCapabilitiesJson()), runtimeCapabilities)) {
            existing.setRuntimeCapabilitiesJson(runtimeCapabilities);
            changed = true;
        }
        if (changed) {
            mapper.updateById(existing);
        }
    }

    private DatasourceTypeCapabilityEntity findEnabledEntity(String typeCode) {
        String normalized = normalize(typeCode);
        if (normalized.isEmpty()) {
            return null;
        }
        return mapper.selectOne(baseCapabilityQuery()
                .eq("tenant_id", "default")
                .eq("type_code", normalized)
                .eq("enabled", Integer.valueOf(1))
                .last("limit 1"));
    }

    private DatasourceTypeCapabilityEntity findEnabledEntityByRuntimePlugin(String pluginType, String role) {
        String normalized = normalizePlugin(pluginType);
        if (normalized.isEmpty()) {
            return null;
        }
        List<DatasourceTypeCapabilityEntity> entities = mapper.selectList(baseCapabilityQuery()
                .eq("tenant_id", "default")
                .eq("enabled", Integer.valueOf(1)));
        for (DatasourceTypeCapabilityEntity entity : entities) {
            List<String> plugins = "writer".equalsIgnoreCase(role)
                    ? entity.getWriterPluginsJson()
                    : entity.getReaderPluginsJson();
            for (String plugin : safeList(plugins)) {
                if (normalized.equals(normalizePlugin(plugin))) {
                    return entity;
                }
            }
        }
        return null;
    }

    private DatasourceTypeCapabilityView toView(DatasourceTypeCapabilityEntity entity) {
        DatasourceTypeCapabilityView view = new DatasourceTypeCapabilityView();
        view.setId(entity.getId());
        view.setTenantId(entity.getTenantId());
        view.setDeleted(entity.getDeleted() != null && entity.getDeleted().intValue() == 1);
        view.setCreatedAt(entity.getCreatedAt());
        view.setUpdatedAt(entity.getUpdatedAt());
        view.setTypeCode(entity.getTypeCode());
        view.setTypeName(entity.getTypeName());
        view.setEnabled(enabled(entity.getEnabled()));
        view.setReadable(enabled(entity.getReadable()));
        view.setWritable(enabled(entity.getWritable()));
        view.setExecutable(enabled(entity.getExecutable()));
        view.setSqlExecutable(enabled(entity.getSqlExecutable()));
        view.setSourceCategory(resolveSourceCategory(entity.getTypeCode(), entity.getSourceCategory()));
        view.setSourcePlugin(entity.getSourcePlugin());
        view.setReaderPlugins(safeList(entity.getReaderPluginsJson()));
        view.setWriterPlugins(safeList(entity.getWriterPluginsJson()));
        view.setRuntimeCapabilities(safeMap(entity.getRuntimeCapabilitiesJson()));
        view.setSortOrder(entity.getSortOrder());
        view.setDescription(entity.getDescription());
        return view;
    }

    private static int flag(boolean value) {
        return value ? 1 : 0;
    }

    private boolean enabled(Integer value) {
        return value != null && value.intValue() == 1;
    }

    private boolean sameStringList(List<String> actual, List<String> expected) {
        List<String> left = safeList(actual);
        List<String> right = safeList(expected);
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!left.get(index).equalsIgnoreCase(right.get(index))) {
                return false;
            }
        }
        return true;
    }

    private List<String> safeList(List<String> source) {
        List<String> result = new ArrayList<String>();
        if (source == null) {
            return result;
        }
        for (String item : source) {
            if (item != null && !item.trim().isEmpty()) {
                result.add(item.trim());
            }
        }
        Collections.sort(result, new Comparator<String>() {
            @Override
            public int compare(String left, String right) {
                return left.compareToIgnoreCase(right);
            }
        });
        return result;
    }

    private StudioException unsupported(String capability, String typeCode) {
        return new StudioException(StudioErrorCode.BAD_REQUEST,
                "Datasource type " + typeCode + " is not " + capability + " by datasource_type_capability");
    }

    public static String normalizeTypeCode(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ENGLISH);
        if ("file".equals(normalized) || "local_file".equals(normalized)) {
            return "local";
        }
        if ("aliyun".equals(normalized) || "aliyun_oss".equals(normalized)
                || "aliyun-oss".equals(normalized)) {
            return "oss";
        }
        return normalized;
    }

    private static String normalize(String value) {
        return normalizeTypeCode(value);
    }

    private static String normalizePlugin(String value) {
        String normalized = normalize(value);
        if (normalized.endsWith("reader")) {
            return normalized.substring(0, normalized.length() - "reader".length());
        }
        if (normalized.endsWith("writer")) {
            return normalized.substring(0, normalized.length() - "writer".length());
        }
        return normalized;
    }

    private static String normalizeSourceCategory(String value) {
        if (value == null || value.trim().isEmpty()) {
            return CATEGORY_DATABASE;
        }
        return value.trim().toUpperCase(Locale.ENGLISH);
    }

    private String resolveSourceCategory(String typeCode, String sourceCategory) {
        if (sourceCategory != null && !sourceCategory.trim().isEmpty()) {
            return normalizeSourceCategory(sourceCategory);
        }
        String normalizedTypeCode = normalize(typeCode);
        for (DefaultCapability capability : DEFAULT_CAPABILITIES) {
            if (capability.typeCode.equalsIgnoreCase(normalizedTypeCode)) {
                return capability.sourceCategory;
            }
        }
        return CATEGORY_DATABASE;
    }

    private QueryWrapper<DatasourceTypeCapabilityEntity> baseCapabilityQuery() {
        QueryWrapper<DatasourceTypeCapabilityEntity> query = new QueryWrapper<DatasourceTypeCapabilityEntity>();
        List<String> columns = new ArrayList<String>(Arrays.asList(
                "id",
                "tenant_id",
                "deleted",
                "created_at",
                "updated_at",
                "type_code",
                "type_name",
                "enabled",
                "readable",
                "writable",
                "executable",
                "sql_executable",
                "source_plugin",
                "reader_plugins_json",
                "writer_plugins_json",
                "sort_order",
                "description"
        ));
        if (hasSourceCategoryColumn()) {
            columns.add("source_category");
        }
        if (hasRuntimeCapabilitiesColumn()) {
            columns.add("runtime_capabilities_json");
        }
        query.select(columns);
        return query;
    }

    private boolean hasSourceCategoryColumn() {
        return hasColumn("source_category");
    }

    private boolean hasRuntimeCapabilitiesColumn() {
        return hasColumn("runtime_capabilities_json");
    }

    private boolean hasColumn(String columnName) {
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            DatabaseMetaData metaData = connection.getMetaData();
            ResultSet lowerCaseColumns = metaData.getColumns(connection.getCatalog(), null,
                    "datasource_type_capability", columnName.toLowerCase(Locale.ENGLISH));
            try {
                if (lowerCaseColumns.next()) {
                    return true;
                }
            } finally {
                lowerCaseColumns.close();
            }
            ResultSet upperCaseColumns = metaData.getColumns(connection.getCatalog(), null,
                    "DATASOURCE_TYPE_CAPABILITY", columnName.toUpperCase(Locale.ENGLISH));
            try {
                return upperCaseColumns.next();
            } finally {
                upperCaseColumns.close();
            }
        }));
    }

    private static Map<String, Object> runtimeCapabilitiesFor(String typeCode) {
        String normalized = normalize(typeCode);
        if (!Set.of("local", "ftp", "sftp", "minio", "oss").contains(normalized)) {
            return new LinkedHashMap<String, Object>();
        }
        Map<String, Object> binaryFile = new LinkedHashMap<String, Object>();
        binaryFile.put("browse", Boolean.TRUE);
        binaryFile.put("read", Boolean.TRUE);
        binaryFile.put("write", Boolean.TRUE);
        binaryFile.put("manage", Boolean.TRUE);
        binaryFile.put("transferSource", Boolean.TRUE);
        binaryFile.put("transferTarget", Boolean.TRUE);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("binaryFile", binaryFile);
        return result;
    }

    private static Map<String, Object> safeMap(Object source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (source instanceof Map<?, ?> values) {
            values.forEach((key, value) -> {
                if (key != null) {
                    result.put(String.valueOf(key), value);
                }
            });
        }
        return result;
    }

    private static List<String> list(String... items) {
        List<String> result = new ArrayList<String>();
        if (items != null) {
            for (String item : items) {
                if (item != null && !item.trim().isEmpty()) {
                    result.add(item.trim());
                }
            }
        }
        return result;
    }

    private static DefaultCapability findDefaultCapability(String typeCode) {
        String normalizedTypeCode = normalize(typeCode);
        for (DefaultCapability capability : DEFAULT_CAPABILITIES) {
            if (capability.typeCode.equalsIgnoreCase(normalizedTypeCode)) {
                return capability;
            }
        }
        return null;
    }

    private static DefaultCapability capability(String typeCode,
                                                String typeName,
                                                String sourceCategory,
                                                boolean enabled,
                                                boolean readable,
                                                boolean writable,
                                                boolean executable,
                                                boolean sqlExecutable,
                                                String sourcePlugin,
                                                List<String> readerPlugins,
                                                List<String> writerPlugins,
                                                int sortOrder,
                                                String description) {
        return new DefaultCapability(typeCode, typeName, enabled, readable, writable, executable,
                sqlExecutable, sourceCategory, sourcePlugin, readerPlugins, writerPlugins, sortOrder, description);
    }

    private static final class DefaultCapability {
        private final String typeCode;
        private final String typeName;
        private final boolean enabled;
        private final boolean readable;
        private final boolean writable;
        private final boolean executable;
        private final boolean sqlExecutable;
        private final String sourceCategory;
        private final String sourcePlugin;
        private final List<String> readerPlugins;
        private final List<String> writerPlugins;
        private final int sortOrder;
        private final String description;

        private DefaultCapability(String typeCode,
                                  String typeName,
                                  boolean enabled,
                                  boolean readable,
                                  boolean writable,
                                  boolean executable,
                                  boolean sqlExecutable,
                                  String sourceCategory,
                                  String sourcePlugin,
                                  List<String> readerPlugins,
                                  List<String> writerPlugins,
                                  int sortOrder,
                                  String description) {
            this.typeCode = typeCode;
            this.typeName = typeName;
            this.enabled = enabled;
            this.readable = readable;
            this.writable = writable;
            this.executable = executable;
            this.sqlExecutable = sqlExecutable;
            this.sourceCategory = sourceCategory;
            this.sourcePlugin = sourcePlugin;
            this.readerPlugins = readerPlugins;
            this.writerPlugins = writerPlugins;
            this.sortOrder = sortOrder;
            this.description = description;
        }
    }
}
