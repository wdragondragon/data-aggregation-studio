package com.jdragon.studio.infra.service;

import com.alibaba.fastjson.JSONObject;
import com.jdragon.studio.dto.enums.FieldComponentType;
import com.jdragon.studio.dto.enums.FieldValueType;
import com.jdragon.studio.dto.enums.MetadataScope;
import com.jdragon.studio.dto.model.MetadataFieldDefinition;
import com.jdragon.studio.dto.model.MetadataSchemaDefinition;
import com.jdragon.studio.dto.model.request.MetadataSchemaSaveRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Service
public class StandardRuntimeOptionSchemaBootstrapService {

    private static final String OBJECT_TYPE = "collection-runtime-option";

    private final MetadataSchemaService metadataSchemaService;
    private final DatasourceTypeCapabilityService datasourceTypeCapabilityService;

    public StandardRuntimeOptionSchemaBootstrapService(MetadataSchemaService metadataSchemaService,
                                                       DatasourceTypeCapabilityService datasourceTypeCapabilityService) {
        this.metadataSchemaService = metadataSchemaService;
        this.datasourceTypeCapabilityService = datasourceTypeCapabilityService;
    }

    @Transactional
    public List<MetadataSchemaDefinition> syncStandardRuntimeOptionSchemas() {
        datasourceTypeCapabilityService.syncStandardRuntimePluginCapabilities();

        List<MetadataSchemaDefinition> result = new ArrayList<MetadataSchemaDefinition>();
        result.add(ensureRuntimeOptionSchema("reader", "mysql8", "MYSQL8 Reader 参数", buildRdbmsReaderFields()));
        result.add(ensureRuntimeOptionSchema("reader", "dm", "DM Reader 参数", buildRdbmsReaderFields()));
        result.add(ensureRuntimeOptionSchema("reader", "postgresql", "PostgreSQL Reader 参数", buildRdbmsReaderFields()));
        result.add(ensureRuntimeOptionSchema("reader", "tbds-hive2", "TBDS Hive2 Reader 参数", buildRdbmsReaderFields()));
        result.add(ensureRuntimeOptionSchema("reader", "influxdbv1", "InfluxDB v1 Reader 参数", buildInfluxdbV1ReaderFields()));
        result.add(ensureRuntimeOptionSchema("reader", "fusion", "Fusion Reader 参数", buildFusionReaderFields()));
        result.add(ensureRuntimeOptionSchema("reader", "ftp", "FTP Reader 参数", buildFileTableReaderFields()));
        result.add(ensureRuntimeOptionSchema("reader", "sftp", "SFTP Reader 参数", buildFileTableReaderFields()));
        result.add(ensureRuntimeOptionSchema("reader", "minio", "MinIO Reader 参数", buildFileTableReaderFields()));
        result.add(ensureRuntimeOptionSchema("reader", "http", "HTTP Reader 参数", buildHttpReaderFields()));
        result.add(ensureRuntimeOptionSchema("reader", "odps", "ODPS Reader 参数", buildOdpsReaderFields()));

        result.add(ensureRuntimeOptionSchema("writer", "mysql8", "MYSQL8 Writer 参数", buildRdbmsWriterFields(Arrays.asList("insert", "replace", "update"))));
        result.add(ensureRuntimeOptionSchema("writer", "dm", "DM Writer 参数", buildRdbmsWriterFields(Arrays.asList("insert", "replace"))));
        result.add(ensureRuntimeOptionSchema("writer", "postgresql", "PostgreSQL Writer 参数", buildRdbmsWriterFields(Arrays.asList("insert", "update", "copy"))));
        result.add(ensureRuntimeOptionSchema("writer", "influxdbv1", "InfluxDB v1 Writer 参数", Collections.singletonList(
                field("batchSize", "批量写入大小", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 10, "500"))));
        result.add(ensureRuntimeOptionSchema("writer", "ftp", "FTP Writer 参数", buildFileTableWriterFields()));
        result.add(ensureRuntimeOptionSchema("writer", "sftp", "SFTP Writer 参数", buildFileTableWriterFields()));
        result.add(ensureRuntimeOptionSchema("writer", "minio", "MinIO Writer 参数", buildFileTableWriterFields()));
        result.add(ensureRuntimeOptionSchema("writer", "http", "HTTP Writer 参数", buildHttpWriterFields()));
        result.add(ensureRuntimeOptionSchema("writer", "odps", "ODPS Writer 参数", buildOdpsWriterFields()));
        return result;
    }

    private MetadataSchemaDefinition ensureRuntimeOptionSchema(String role,
                                                               String pluginType,
                                                               String schemaName,
                                                               List<MetadataFieldDefinition> fields) {
        MetadataSchemaDefinition existing = metadataSchemaService.findRuntimeOptionSchema(role, pluginType);
        MetadataSchemaSaveRequest request = new MetadataSchemaSaveRequest();
        request.setSchemaId(existing == null ? null : existing.getId());
        request.setSchemaCode("runtime:" + role + ":" + pluginType);
        request.setSchemaName(schemaName);
        request.setObjectType(OBJECT_TYPE);
        request.setTypeCode(role + ":" + pluginType);
        request.setDescription(encodeRuntimeDescription(schemaName, role, pluginType));
        request.setFields(fields);
        if (sameRuntimeSchema(existing, request)) {
            return existing;
        }
        return metadataSchemaService.saveDraft(request);
    }

    private String encodeRuntimeDescription(String schemaName, String role, String pluginType) {
        JSONObject config = new JSONObject(true);
        config.put("domain", "RUNTIME");
        config.put("role", role);
        config.put("pluginType", pluginType);
        config.put("metaModelCode", role);
        config.put("metaModelName", schemaName);
        config.put("displayMode", "SINGLE");
        config.put("required", false);
        config.put("syncStrategy", "RUNTIME_OPTION");
        return MetaModelConfigDescriptions.encode(config, schemaName + " runtime options.");
    }

    private List<MetadataFieldDefinition> buildRdbmsReaderFields() {
        List<MetadataFieldDefinition> fields = new ArrayList<MetadataFieldDefinition>();
        fields.add(field("selectSql", "自定义查询 SQL", FieldValueType.STRING, FieldComponentType.SQL_EDITOR, false, false, 10, null));
        fields.add(field("mandatoryEncoding", "字符编码", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 20, "utf-8"));
        return fields;
    }

    private List<MetadataFieldDefinition> buildInfluxdbV1ReaderFields() {
        List<MetadataFieldDefinition> fields = new ArrayList<MetadataFieldDefinition>();
        fields.add(field("selectSql", "自定义查询 SQL", FieldValueType.STRING, FieldComponentType.SQL_EDITOR, false, false, 10, null));
        fields.add(field("startTime", "开始时间", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 20, null));
        fields.add(field("endTime", "结束时间", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 30, null));
        fields.add(field("windowSizeMs", "窗口大小(毫秒)", FieldValueType.LONG, FieldComponentType.NUMBER, false, false, 40, "60000"));
        fields.add(field("pageLimit", "分页条数", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 50, "1000"));
        return fields;
    }

    private List<MetadataFieldDefinition> buildFileTableReaderFields() {
        List<MetadataFieldDefinition> fields = new ArrayList<MetadataFieldDefinition>();
        fields.add(field("hasHeader", "CSV 跳过表头", FieldValueType.BOOLEAN, FieldComponentType.SWITCH, false, false, 10, "true"));
        fields.add(field("nullFormat", "CSV 空值标记", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 20, "\\N"));
        fields.add(field("fieldQuote", "CSV 引号字符", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 30, "\""));
        fields.add(field("dataType", "EFILE 数据类型", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 40, null));
        return fields;
    }

    private List<MetadataFieldDefinition> buildHttpReaderFields() {
        List<MetadataFieldDefinition> fields = new ArrayList<MetadataFieldDefinition>();
        fields.add(field("contentType", "Content-Type", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 10,
                "application/json;charset=utf-8"));
        fields.add(field("header", "请求头", FieldValueType.STRING, FieldComponentType.JSON_EDITOR, false, false, 20, "{}"));
        fields.add(field("params", "请求参数", FieldValueType.STRING, FieldComponentType.JSON_EDITOR, false, false, 30, "{}"));
        fields.add(field("requestBody", "请求体", FieldValueType.STRING, FieldComponentType.JSON_EDITOR, false, false, 40, ""));
        fields.add(field("pageRead", "启用分页读取", FieldValueType.BOOLEAN, FieldComponentType.SWITCH, false, false, 50, "false"));
        fields.add(field("pageSize", "分页大小", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 60, "500"));
        return fields;
    }

    private List<MetadataFieldDefinition> buildHttpWriterFields() {
        List<MetadataFieldDefinition> fields = new ArrayList<MetadataFieldDefinition>();
        fields.add(field("contentType", "Content-Type", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 10,
                "application/json;charset=utf-8"));
        fields.add(field("header", "请求头", FieldValueType.STRING, FieldComponentType.JSON_EDITOR, false, false, 20, "{}"));
        fields.add(field("params", "请求参数", FieldValueType.STRING, FieldComponentType.JSON_EDITOR, false, false, 30, "{}"));
        fields.add(field("requestBody", "请求体模板", FieldValueType.STRING, FieldComponentType.JSON_EDITOR, false, false, 40, ""));
        fields.add(field("payloadMode", "发送数据形态", FieldValueType.STRING, FieldComponentType.SELECT, false, false, 50,
                "object", Arrays.asList("object", "array")));
        fields.add(field("dataNodePath", "发送数据节点", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 60, null));
        fields.add(field("includeTotal", "携带发送总数", FieldValueType.BOOLEAN, FieldComponentType.SWITCH, false, false, 70, "false"));
        fields.add(field("totalNodePath", "发送总数节点", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 80, null));
        fields.add(field("batchSize", "数组批量大小", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 90, "500"));
        fields.add(field("responseStatus.path", "业务状态节点", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 100, null));
        fields.add(field("responseStatus.code", "业务成功状态码", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 110, "200"));
        fields.add(field("retryTimes", "重试次数", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 120, "3"));
        fields.add(field("retryIntervalMs", "重试间隔(毫秒)", FieldValueType.LONG, FieldComponentType.NUMBER, false, false, 130, "1000"));
        fields.add(field("connectTimeoutMs", "连接超时(毫秒)", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 140, "3000"));
        fields.add(field("socketTimeoutMs", "响应超时(毫秒)", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 150, "3000"));
        return fields;
    }

    private List<MetadataFieldDefinition> buildOdpsReaderFields() {
        List<MetadataFieldDefinition> fields = new ArrayList<MetadataFieldDefinition>();
        fields.add(field("readMode", "读取模式", FieldValueType.STRING, FieldComponentType.SELECT, false, false, 10,
                "auto", Arrays.asList("auto", "tunnel", "sql")));
        fields.add(field("selectSql", "自定义查询 SQL", FieldValueType.STRING, FieldComponentType.SQL_EDITOR, false, false, 20, null));
        fields.add(field("partitionSpec", "分区条件", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 30, null));
        fields.add(field("includePartitionColumns", "读取分区字段", FieldValueType.BOOLEAN, FieldComponentType.SWITCH, false, false, 40, "false"));
        fields.add(field("offset", "起始偏移", FieldValueType.LONG, FieldComponentType.NUMBER, false, false, 50, "0"));
        fields.add(field("maxRows", "最大读取行数", FieldValueType.LONG, FieldComponentType.NUMBER, false, false, 60, "0"));
        return fields;
    }

    private List<MetadataFieldDefinition> buildOdpsWriterFields() {
        List<MetadataFieldDefinition> fields = new ArrayList<MetadataFieldDefinition>();
        fields.add(field("writeMode", "写入模式", FieldValueType.STRING, FieldComponentType.SELECT, true, false, 10,
                "append", Arrays.asList("append", "overwrite")));
        fields.add(field("partitionSpec", "静态分区", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 20, null));
        fields.add(field("partitionColumns", "动态分区字段", FieldValueType.ARRAY, FieldComponentType.SELECT, false, false, 30, "[]"));
        fields.add(field("batchSize", "批量写入大小", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 40, "1000"));
        fields.add(field("emptyAsNull", "空字符串写入 NULL", FieldValueType.BOOLEAN, FieldComponentType.SWITCH, false, false, 50, "false"));
        fields.add(field("autoCreatePartition", "自动创建分区", FieldValueType.BOOLEAN, FieldComponentType.SWITCH, false, false, 60, "true"));
        fields.add(field("preSql", "写入前 SQL", FieldValueType.STRING, FieldComponentType.SQL_EDITOR, false, false, 70, null));
        fields.add(field("postSql", "写入后 SQL", FieldValueType.STRING, FieldComponentType.SQL_EDITOR, false, false, 80, null));
        return fields;
    }

    private List<MetadataFieldDefinition> buildRdbmsWriterFields(List<String> writeModeOptions) {
        List<MetadataFieldDefinition> fields = new ArrayList<MetadataFieldDefinition>();
        fields.add(field("writeMode", "写入模式", FieldValueType.STRING, FieldComponentType.SELECT, true, false, 10, "insert", writeModeOptions));
        fields.add(field("pkColumn", "主键字段", FieldValueType.ARRAY, FieldComponentType.SELECT, false, false, 20, "[]"));
        fields.add(field("batchSize", "批量写入大小", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 30, "1024"));
        fields.add(field("emptyAsNull", "空字符串写入 NULL", FieldValueType.BOOLEAN, FieldComponentType.SWITCH, false, false, 40, "false"));
        return fields;
    }

    private List<MetadataFieldDefinition> buildFileTableWriterFields() {
        List<MetadataFieldDefinition> fields = new ArrayList<MetadataFieldDefinition>();
        fields.add(field("writeMode", "写入模式", FieldValueType.STRING, FieldComponentType.SELECT, true, false, 10,
                "overwrite", Arrays.asList("overwrite", "append", "failIfExists")));
        fields.add(field("hasHeader", "CSV/Excel 写入表头", FieldValueType.BOOLEAN, FieldComponentType.SWITCH, false, false, 20, "true"));
        fields.add(field("nullFormat", "CSV 空值标记", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 30, "\\N"));
        fields.add(field("fieldQuote", "CSV 引号字符", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 40, "\""));
        fields.add(field("sheetName", "Excel Sheet 名称", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 50, "Sheet1"));
        return fields;
    }

    private List<MetadataFieldDefinition> buildFusionReaderFields() {
        List<MetadataFieldDefinition> fields = new ArrayList<MetadataFieldDefinition>();
        fields.add(field("defaultStrategy", "默认融合策略", FieldValueType.STRING, FieldComponentType.SELECT, false, false, 10,
                "WEIGHTED_AVERAGE", Arrays.asList("WEIGHTED_AVERAGE", "PRIORITY", "HIGH_CONFIDENCE", "MAJORITY_VOTE")));
        fields.add(field("errorMode", "错误处理模式", FieldValueType.STRING, FieldComponentType.SELECT, false, false, 20,
                "LENIENT", Arrays.asList("STRICT", "LENIENT", "MIXED")));
        fields.add(field("performance.parallelSourceCount", "并行源数量", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 30, "2"));
        fields.add(field("performance.memoryLimitMB", "内存上限(MB)", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 40, "1024"));
        fields.add(field("cache.partitionCount", "缓存分区数", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 50, "10"));
        fields.add(field("adaptiveMerge.enabled", "启用自适应合并", FieldValueType.BOOLEAN, FieldComponentType.SWITCH, false, false, 60, "true"));
        fields.add(field("adaptiveMerge.pendingKeyThreshold", "待合并 Key 阈值", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 70, "4096"));
        fields.add(field("adaptiveMerge.pendingMemoryMB", "待合并内存(MB)", FieldValueType.INTEGER, FieldComponentType.NUMBER, false, false, 80, "512"));
        fields.add(field("adaptiveMerge.overflowSpillPath", "溢出落盘路径", FieldValueType.STRING, FieldComponentType.INPUT, false, false, 90, null));
        return fields;
    }

    private MetadataFieldDefinition field(String fieldKey,
                                          String fieldName,
                                          FieldValueType valueType,
                                          FieldComponentType componentType,
                                          boolean required,
                                          boolean sensitive,
                                          int sortOrder,
                                          String defaultValue) {
        return field(fieldKey, fieldName, valueType, componentType, required, sensitive, sortOrder, defaultValue,
                Collections.<String>emptyList());
    }

    private MetadataFieldDefinition field(String fieldKey,
                                          String fieldName,
                                          FieldValueType valueType,
                                          FieldComponentType componentType,
                                          boolean required,
                                          boolean sensitive,
                                          int sortOrder,
                                          String defaultValue,
                                          List<String> options) {
        MetadataFieldDefinition field = new MetadataFieldDefinition();
        field.setFieldKey(fieldKey);
        field.setFieldName(fieldName);
        field.setDescription(fieldName);
        field.setScope(MetadataScope.TECHNICAL);
        field.setValueType(valueType);
        field.setComponentType(componentType);
        field.setRequired(required);
        field.setSensitive(sensitive);
        field.setSortOrder(sortOrder);
        field.setDefaultValue(defaultValue);
        field.setOptions(options == null ? new ArrayList<String>() : new ArrayList<String>(options));
        applyQueryCapabilities(field);
        return field;
    }

    private void applyQueryCapabilities(MetadataFieldDefinition field) {
        if (field == null) {
            return;
        }
        if (Boolean.TRUE.equals(field.getSensitive())) {
            field.setSearchable(false);
            field.setSortable(false);
            field.setQueryOperators(new ArrayList<String>());
            field.setQueryDefaultOperator(null);
            return;
        }
        List<String> operators = defaultQueryOperators(field.getValueType());
        field.setSearchable(!operators.isEmpty());
        field.setSortable(isSortableValueType(field.getValueType()));
        field.setQueryOperators(operators);
        field.setQueryDefaultOperator(operators.isEmpty() ? null : defaultQueryOperator(field.getValueType()));
    }

    private List<String> defaultQueryOperators(FieldValueType valueType) {
        List<String> operators = new ArrayList<String>();
        if (valueType == null) {
            return operators;
        }
        switch (valueType) {
            case STRING:
                operators.add("EQ");
                operators.add("LIKE");
                operators.add("IN");
                return operators;
            case BOOLEAN:
                operators.add("EQ");
                return operators;
            case INTEGER:
            case LONG:
            case DECIMAL:
                operators.add("EQ");
                operators.add("GT");
                operators.add("GE");
                operators.add("LT");
                operators.add("LE");
                operators.add("BETWEEN");
                operators.add("IN");
                return operators;
            default:
                return operators;
        }
    }

    private String defaultQueryOperator(FieldValueType valueType) {
        if (valueType == null) {
            return null;
        }
        return FieldValueType.STRING == valueType ? "LIKE" : "EQ";
    }

    private boolean isSortableValueType(FieldValueType valueType) {
        return FieldValueType.STRING == valueType
                || FieldValueType.BOOLEAN == valueType
                || FieldValueType.INTEGER == valueType
                || FieldValueType.LONG == valueType
                || FieldValueType.DECIMAL == valueType;
    }

    private boolean sameRuntimeSchema(MetadataSchemaDefinition existing, MetadataSchemaSaveRequest expected) {
        if (existing == null) {
            return false;
        }
        return Objects.equals(existing.getSchemaCode(), expected.getSchemaCode())
                && Objects.equals(existing.getSchemaName(), expected.getSchemaName())
                && Objects.equals(existing.getObjectType(), expected.getObjectType())
                && Objects.equals(existing.getTypeCode(), expected.getTypeCode())
                && sameFields(existing.getFields(), expected.getFields());
    }

    private boolean sameFields(List<MetadataFieldDefinition> actual, List<MetadataFieldDefinition> expected) {
        if (actual == null || expected == null || actual.size() != expected.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            if (!sameField(actual.get(index), expected.get(index))) {
                return false;
            }
        }
        return true;
    }

    private boolean sameField(MetadataFieldDefinition actual, MetadataFieldDefinition expected) {
        return actual != null && expected != null
                && Objects.equals(actual.getFieldKey(), expected.getFieldKey())
                && Objects.equals(actual.getFieldName(), expected.getFieldName())
                && Objects.equals(actual.getScope(), expected.getScope())
                && Objects.equals(actual.getValueType(), expected.getValueType())
                && Objects.equals(actual.getComponentType(), expected.getComponentType())
                && Objects.equals(actual.getRequired(), expected.getRequired())
                && Objects.equals(actual.getSensitive(), expected.getSensitive())
                && Objects.equals(actual.getSortOrder(), expected.getSortOrder())
                && Objects.equals(actual.getDefaultValue(), expected.getDefaultValue())
                && sameStringList(actual.getOptions(), expected.getOptions());
    }

    private boolean sameStringList(List<String> actual, List<String> expected) {
        List<String> left = actual == null ? Collections.<String>emptyList() : actual;
        List<String> right = expected == null ? Collections.<String>emptyList() : expected;
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!Objects.equals(left.get(index), right.get(index))) {
                return false;
            }
        }
        return true;
    }
}
