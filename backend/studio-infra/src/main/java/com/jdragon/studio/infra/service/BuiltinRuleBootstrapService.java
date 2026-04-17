package com.jdragon.studio.infra.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jdragon.studio.commons.constant.StudioConstants;
import com.jdragon.studio.dto.enums.QualityRuleDimension;
import com.jdragon.studio.dto.enums.QualityRuleGranularity;
import com.jdragon.studio.dto.enums.QualityRuleOutputType;
import com.jdragon.studio.dto.enums.QualityRuleParamType;
import com.jdragon.studio.dto.enums.QualityRuleScopeType;
import com.jdragon.studio.dto.model.request.FieldMappingRuleParamSaveRequest;
import com.jdragon.studio.dto.model.request.FieldMappingRuleSaveRequest;
import com.jdragon.studio.dto.model.request.QualityRuleInputParamSaveRequest;
import com.jdragon.studio.dto.model.request.QualityRuleOutputParamSaveRequest;
import com.jdragon.studio.dto.model.request.QualityRuleSaveRequest;
import com.jdragon.studio.infra.entity.FieldMappingRuleEntity;
import com.jdragon.studio.infra.entity.FieldMappingRuleParamEntity;
import com.jdragon.studio.infra.entity.QualityRuleEntity;
import com.jdragon.studio.infra.entity.QualityRuleInputParamEntity;
import com.jdragon.studio.infra.entity.QualityRuleOutputParamEntity;
import com.jdragon.studio.infra.entity.StudioUserEntity;
import com.jdragon.studio.infra.mapper.FieldMappingRuleMapper;
import com.jdragon.studio.infra.mapper.FieldMappingRuleParamMapper;
import com.jdragon.studio.infra.mapper.QualityRuleInputParamMapper;
import com.jdragon.studio.infra.mapper.QualityRuleMapper;
import com.jdragon.studio.infra.mapper.QualityRuleOutputParamMapper;
import com.jdragon.studio.infra.mapper.StudioUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Service
public class BuiltinRuleBootstrapService {

    private static final String BUILTIN_FIELD_MAPPING_DESCRIPTION = "系统内置字段映射规则";

    private final FieldMappingRuleMapper fieldMappingRuleMapper;
    private final FieldMappingRuleParamMapper fieldMappingRuleParamMapper;
    private final QualityRuleMapper qualityRuleMapper;
    private final QualityRuleInputParamMapper qualityRuleInputParamMapper;
    private final QualityRuleOutputParamMapper qualityRuleOutputParamMapper;
    private final StudioUserMapper studioUserMapper;

    public BuiltinRuleBootstrapService(FieldMappingRuleMapper fieldMappingRuleMapper,
                                       FieldMappingRuleParamMapper fieldMappingRuleParamMapper,
                                       QualityRuleMapper qualityRuleMapper,
                                       QualityRuleInputParamMapper qualityRuleInputParamMapper,
                                       QualityRuleOutputParamMapper qualityRuleOutputParamMapper,
                                       StudioUserMapper studioUserMapper) {
        this.fieldMappingRuleMapper = fieldMappingRuleMapper;
        this.fieldMappingRuleParamMapper = fieldMappingRuleParamMapper;
        this.qualityRuleMapper = qualityRuleMapper;
        this.qualityRuleInputParamMapper = qualityRuleInputParamMapper;
        this.qualityRuleOutputParamMapper = qualityRuleOutputParamMapper;
        this.studioUserMapper = studioUserMapper;
    }

    @Transactional
    public void bootstrap() {
        Long adminUserId = resolveDefaultAdminUserId();
        bootstrapFieldMappingRules(adminUserId);
        bootstrapQualityRules(adminUserId);
    }

    private void bootstrapFieldMappingRules(Long adminUserId) {
        for (FieldMappingRuleSaveRequest definition : builtinFieldMappingRules()) {
            FieldMappingRuleEntity existing = fieldMappingRuleMapper.selectOne(new LambdaQueryWrapper<FieldMappingRuleEntity>()
                    .eq(FieldMappingRuleEntity::getMappingCode, definition.getMappingCode())
                    .last("limit 1"));
            if (existing == null) {
                LocalDateTime now = LocalDateTime.now();
                FieldMappingRuleEntity entity = new FieldMappingRuleEntity();
                entity.setMappingName(definition.getMappingName());
                entity.setMappingType(definition.getMappingType());
                entity.setMappingCode(definition.getMappingCode());
                entity.setEnabled(Boolean.FALSE.equals(definition.getEnabled()) ? Integer.valueOf(0) : Integer.valueOf(1));
                entity.setDescription(definition.getDescription());
                entity.setCreatedBy(adminUserId);
                entity.setDeleted(Integer.valueOf(0));
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);
                fieldMappingRuleMapper.insert(entity);
                insertFieldMappingParams(entity.getId(), definition.getParams());
                continue;
            }
            if (shouldRefreshFieldMappingParams(existing.getId(), definition.getParams())) {
                fieldMappingRuleParamMapper.delete(new LambdaQueryWrapper<FieldMappingRuleParamEntity>()
                        .eq(FieldMappingRuleParamEntity::getRuleId, existing.getId()));
                insertFieldMappingParams(existing.getId(), definition.getParams());
            }
        }
    }

    private void insertFieldMappingParams(Long ruleId, List<FieldMappingRuleParamSaveRequest> params) {
        if (ruleId == null || params == null) {
            return;
        }
        for (FieldMappingRuleParamSaveRequest param : params) {
            LocalDateTime now = LocalDateTime.now();
            FieldMappingRuleParamEntity entity = new FieldMappingRuleParamEntity();
            entity.setRuleId(ruleId);
            entity.setParamName(param.getParamName());
            entity.setParamOrder(param.getParamOrder());
            entity.setComponentType(param.getComponentType());
            entity.setParamValueJson(param.getParamValueJson());
            entity.setDescription(param.getDescription());
            entity.setDeleted(Integer.valueOf(0));
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            fieldMappingRuleParamMapper.insert(entity);
        }
    }

    private boolean shouldRefreshFieldMappingParams(Long ruleId, List<FieldMappingRuleParamSaveRequest> definitions) {
        if (ruleId == null) {
            return false;
        }
        int expected = definitions == null ? 0 : definitions.size();
        long actual = fieldMappingRuleParamMapper.selectCount(new LambdaQueryWrapper<FieldMappingRuleParamEntity>()
                .eq(FieldMappingRuleParamEntity::getRuleId, ruleId));
        return actual != expected;
    }

    private void bootstrapQualityRules(Long adminUserId) {
        for (QualityRuleSaveRequest definition : builtinQualityRules()) {
            QualityRuleEntity existing = qualityRuleMapper.selectOne(new LambdaQueryWrapper<QualityRuleEntity>()
                    .eq(QualityRuleEntity::getTenantId, StudioConstants.DEFAULT_TENANT_ID)
                    .eq(QualityRuleEntity::getScopeType, QualityRuleScopeType.SYSTEM.name())
                    .eq(QualityRuleEntity::getRuleCode, definition.getRuleCode())
                    .last("limit 1"));
            if (existing == null) {
                LocalDateTime now = LocalDateTime.now();
                QualityRuleEntity entity = new QualityRuleEntity();
                entity.setTenantId(StudioConstants.DEFAULT_TENANT_ID);
                entity.setProjectId(null);
                entity.setCreatedBy(adminUserId);
                entity.setRuleName(definition.getRuleName());
                entity.setRuleCode(definition.getRuleCode());
                entity.setScopeType(definition.getScopeType().name());
                entity.setRuleDimension(definition.getRuleDimension().name());
                entity.setDescription(definition.getDescription());
                entity.setSupportedDatasourceTypesJson(new ArrayList<String>(definition.getSupportedDatasourceTypes()));
                entity.setGranularity(definition.getGranularity().name());
                entity.setLogicSql(definition.getLogicSql());
                entity.setEnabled(Boolean.FALSE.equals(definition.getEnabled()) ? Integer.valueOf(0) : Integer.valueOf(1));
                entity.setDeleted(Integer.valueOf(0));
                entity.setCreatedAt(now);
                entity.setUpdatedAt(now);
                qualityRuleMapper.insert(entity);
                insertQualityRuleChildren(entity.getId(), definition);
                continue;
            }
            if (shouldRefreshQualityRuleChildren(existing.getId(), definition)) {
                qualityRuleInputParamMapper.delete(new LambdaQueryWrapper<QualityRuleInputParamEntity>()
                        .eq(QualityRuleInputParamEntity::getRuleId, existing.getId()));
                qualityRuleOutputParamMapper.delete(new LambdaQueryWrapper<QualityRuleOutputParamEntity>()
                        .eq(QualityRuleOutputParamEntity::getRuleId, existing.getId()));
                insertQualityRuleChildren(existing.getId(), definition);
            }
        }
    }

    private boolean shouldRefreshQualityRuleChildren(Long ruleId, QualityRuleSaveRequest definition) {
        if (ruleId == null || definition == null) {
            return false;
        }
        int expectedInputCount = definition.getInputParams() == null ? 0 : definition.getInputParams().size();
        int expectedOutputCount = definition.getOutputParams() == null ? 0 : definition.getOutputParams().size();
        long actualInputCount = qualityRuleInputParamMapper.selectCount(new LambdaQueryWrapper<QualityRuleInputParamEntity>()
                .eq(QualityRuleInputParamEntity::getRuleId, ruleId));
        long actualOutputCount = qualityRuleOutputParamMapper.selectCount(new LambdaQueryWrapper<QualityRuleOutputParamEntity>()
                .eq(QualityRuleOutputParamEntity::getRuleId, ruleId));
        return actualInputCount != expectedInputCount || actualOutputCount != expectedOutputCount;
    }

    private void insertQualityRuleChildren(Long ruleId, QualityRuleSaveRequest definition) {
        if (ruleId == null || definition == null) {
            return;
        }
        for (QualityRuleInputParamSaveRequest param : safeList(definition.getInputParams())) {
            LocalDateTime now = LocalDateTime.now();
            QualityRuleInputParamEntity entity = new QualityRuleInputParamEntity();
            entity.setRuleId(ruleId);
            entity.setParamOrder(param.getParamOrder());
            entity.setParamName(param.getParamName());
            entity.setParamType(param.getParamType() == null ? null : param.getParamType().name());
            entity.setParamMeaning(param.getParamMeaning());
            entity.setDeleted(Integer.valueOf(0));
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            qualityRuleInputParamMapper.insert(entity);
        }
        for (QualityRuleOutputParamSaveRequest param : safeList(definition.getOutputParams())) {
            LocalDateTime now = LocalDateTime.now();
            QualityRuleOutputParamEntity entity = new QualityRuleOutputParamEntity();
            entity.setRuleId(ruleId);
            entity.setOutputOrder(param.getOutputOrder());
            entity.setResultField(param.getResultField());
            entity.setOutputType(param.getOutputType() == null ? null : param.getOutputType().name());
            entity.setOutputDescription(param.getOutputDescription());
            entity.setDeleted(Integer.valueOf(0));
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            qualityRuleOutputParamMapper.insert(entity);
        }
    }

    private Long resolveDefaultAdminUserId() {
        StudioUserEntity user = studioUserMapper.selectOne(new LambdaQueryWrapper<StudioUserEntity>()
                .eq(StudioUserEntity::getUsername, StudioConstants.DEFAULT_ADMIN_USERNAME)
                .last("limit 1"));
        return user == null ? null : user.getId();
    }

    private List<FieldMappingRuleSaveRequest> builtinFieldMappingRules() {
        return Arrays.asList(
                fieldMappingRule("range_number_filter", "值区间过滤", "过滤",
                        fieldMappingParam(1, "saveOrDelete", "select", "[\"save\",\"delete\"]", "Keep or delete records matched in range"),
                        fieldMappingParam(2, "startValue", "numberPicker", null, "Inclusive range start value"),
                        fieldMappingParam(3, "endValue", "numberPicker", null, "Inclusive range end value")),
                fieldMappingRule("string_operation_filter", "枚举值过滤", "过滤",
                        fieldMappingParam(1, "saveOrDelete", "select", "[\"save\",\"delete\"]", "Keep or delete matched records"),
                        fieldMappingParam(2, "operation", "select", "[\"Yes\",\"No\"]", "Enum matching strategy"),
                        fieldMappingParam(3, "value", "textArea", null, "Comma separated enum values")),
                fieldMappingRule("number_operation_filter", "数字过滤", "过滤",
                        fieldMappingParam(1, "operation", "select", "[\">\",\"<\",\"=\",\"!=\",\">=\",\"<=\"]", "Numeric comparison operator"),
                        fieldMappingParam(2, "value", "numberPicker", null, "Comparison value")),
                fieldMappingRule("date_operation_filter", "日期过滤", "过滤",
                        fieldMappingParam(1, "saveOrDelete", "select", "[\"save\",\"delete\"]", "Keep or delete matched date range records"),
                        fieldMappingParam(2, "valueDf", "input", null, "Date format like yyyy-MM-dd HH:mm:ss"),
                        fieldMappingParam(3, "startValue", "input", null, "Start date string in the same format"),
                        fieldMappingParam(4, "endValue", "input", null, "End date string in the same format")),
                fieldMappingRule("null_value_filter", "空值过滤", "过滤"),
                fieldMappingRule("date_filter", "日期转换成时间戳，非法日期置空", "规整",
                        fieldMappingParam(1, "dateFormat", "input", null, "Input date format like yyyy-MM-dd HH:mm:ss")),
                fieldMappingRule("null_value_replace", "空值替换", "规整",
                        fieldMappingParam(1, "replaceValue", "input", null, "Replacement value for blank input")),
                fieldMappingRule("add_default_value", "添加默认值", "规整",
                        fieldMappingParam(1, "defaultValue", "input", null, "Default value written to target column")),
                fieldMappingRule("insert_sys_time", "添加系统时间", "规整",
                        fieldMappingParam(1, "format", "input", null, "System time output format like yyyy-MM-dd HH:mm:ss")),
                fieldMappingRule("date_transformer", "字符串日期格式转换", "规整",
                        fieldMappingParam(1, "dateFormatOld", "input", null, "Source date format, or stamp for timestamp"),
                        fieldMappingParam(2, "dateFormatNew", "input", null, "Target date format, or stamp for timestamp")),
                fieldMappingRule("trim_spaces_str", "去除字符串前后空格", "规整"),
                fieldMappingRule("value_filter", "字符串日期处理", "规整",
                        fieldMappingParam(1, "stringFormat", "input", null, "Source string date format, supports timestamp"),
                        fieldMappingParam(2, "toDate", "select", "[\"DATE\",\"DATETIME\",\"TIME\"]", "Target date type")),
                fieldMappingRule("replace_str", "替换字符", "规整",
                        fieldMappingParam(1, "regex", "input", null, "Characters or regex patterns, comma separated"),
                        fieldMappingParam(2, "replaceMent", "input", null, "Replacement text")),
                fieldMappingRule("underline_toCamel_str", "下划线转驼峰", "规整"),
                fieldMappingRule("camel_toUnderline_str", "驼峰转下划线", "规整"),
                fieldMappingRule("number_cut", "保留小数点后n位", "规整",
                        fieldMappingParam(1, "num", "numberPicker", null, "Decimal places to keep")),
                fieldMappingRule("SHA256_str", "SHA256加密", "脱敏",
                        fieldMappingParam(1, "key", "input", null, "Optional salt value")),
                fieldMappingRule("string_cut", "字符截取", "脱敏",
                        fieldMappingParam(1, "saveOrDelete", "select", "[\"save\",\"delete\"]", "String cut strategy"),
                        fieldMappingParam(2, "beforeNum", "numberPicker", null, "Head length to save or cut"),
                        fieldMappingParam(3, "afterNum", "numberPicker", null, "Tail length to save or cut")),
                fieldMappingRule("date_mask", "覆盖脱敏", "脱敏",
                        fieldMappingParam(1, "hideOrShow", "select", "[\"hide\",\"show\"]", "Masking strategy"),
                        fieldMappingParam(2, "beforeNum", "numberPicker", null, "Head segment length"),
                        fieldMappingParam(3, "centerNum", "numberPicker", null, "Center segment length"),
                        fieldMappingParam(4, "afterNum", "numberPicker", null, "Tail segment length")),
                fieldMappingRule("MD5_str", "md5加密", "脱敏",
                        fieldMappingParam(1, "key", "input", null, "Optional salt value")),
                fieldMappingRule("sm2_str", "sm2加密", "加密",
                        fieldMappingParam(1, "key", "textArea", null, "Base64 key; public key for encrypt, private key for decrypt"),
                        fieldMappingParam(2, "option", "select", "[\"encrypt\",\"decrypt\"]", "SM2 operation")),
                fieldMappingRule("rsa_str", "rsa加密", "加密",
                        fieldMappingParam(1, "key", "textArea", null, "Base64 key; public key for encrypt, private key for decrypt"),
                        fieldMappingParam(2, "option", "select", "[\"encrypt\",\"decrypt\"]", "RSA operation")),
                fieldMappingRule("idea_str", "idea加密", "加密",
                        fieldMappingParam(1, "key", "input", null, "Base64 encoded 16-byte IDEA key"),
                        fieldMappingParam(2, "option", "select", "[\"encrypt\",\"decrypt\"]", "IDEA operation")),
                fieldMappingRule("3des_str", "3des加密", "加密",
                        fieldMappingParam(1, "key", "input", null, "Base64 encoded 24-byte 3DES key"),
                        fieldMappingParam(2, "option", "select", "[\"encrypt\",\"decrypt\"]", "3DES operation")),
                fieldMappingRule("des_str", "des加密", "加密",
                        fieldMappingParam(1, "key", "input", null, "Base64 encoded 8-byte DES key"),
                        fieldMappingParam(2, "option", "select", "[\"encrypt\",\"decrypt\"]", "DES operation")),
                fieldMappingRule("aes_str", "aes加密", "加密",
                        fieldMappingParam(1, "key", "input", null, "Base64 encoded 16-byte AES key"),
                        fieldMappingParam(2, "option", "select", "[\"encrypt\",\"decrypt\"]", "AES operation"))
        );
    }

    private List<QualityRuleSaveRequest> builtinQualityRules() {
        return Arrays.asList(
                qualityRule("DQ_TABLE_ROW_COUNT", "表行数统计", QualityRuleDimension.COMPLETENESS, QualityRuleGranularity.TABLE,
                        "统计目标表总行数，常用于表级完整性、波动和空表监控。",
                        "select count(*) as row_count from ${Schema_Table}",
                        inputParams(inputParam(1, "Schema_Table", QualityRuleParamType.TABLE, "运行时替换为模型物理表名")),
                        outputParams(outputParam(1, "row_count", QualityRuleOutputType.NUMBER, "目标表总行数"))),
                qualityRule("DQ_COLUMN_NULL_COUNT", "字段空值数统计", QualityRuleDimension.COMPLETENESS, QualityRuleGranularity.COLUMN,
                        "统计字段为空的记录数，适用于必填字段完整性校验。",
                        "select count(*) as null_count from ${Schema_Table} where ${Column} is null",
                        inputParams(
                                inputParam(1, "Schema_Table", QualityRuleParamType.TABLE, "运行时替换为模型物理表名"),
                                inputParam(2, "Column", QualityRuleParamType.COLUMN, "运行时替换为所选字段名")),
                        outputParams(outputParam(1, "null_count", QualityRuleOutputType.NUMBER, "字段为空的记录数"))),
                qualityRule("DQ_COLUMN_FILL_RATE", "字段填充率统计", QualityRuleDimension.COMPLETENESS, QualityRuleGranularity.COLUMN,
                        "统计字段非空占比，适用于字段填充完整性趋势监控。",
                        "select case when count(*) = 0 then 0 else sum(case when ${Column} is not null then 1 else 0 end) * 1.0 / count(*) end as fill_rate from ${Schema_Table}",
                        inputParams(
                                inputParam(1, "Column", QualityRuleParamType.COLUMN, "运行时替换为所选字段名"),
                                inputParam(2, "Schema_Table", QualityRuleParamType.TABLE, "运行时替换为模型物理表名")),
                        outputParams(outputParam(1, "fill_rate", QualityRuleOutputType.NUMBER, "字段非空记录占比"))),
                qualityRule("DQ_STRING_BLANK_COUNT", "字符串空白值统计", QualityRuleDimension.COMPLETENESS, QualityRuleGranularity.COLUMN,
                        "统计字段为空或空白字符串的记录数，适用于文本类必填字段校验。",
                        "select count(*) as blank_count from ${Schema_Table} where ${Column} is null or trim(${Column}) = ''",
                        inputParams(
                                inputParam(1, "Schema_Table", QualityRuleParamType.TABLE, "运行时替换为模型物理表名"),
                                inputParam(2, "Column", QualityRuleParamType.COLUMN, "运行时替换为所选字段名")),
                        outputParams(outputParam(1, "blank_count", QualityRuleOutputType.NUMBER, "字段为空或空白字符串的记录数"))),
                qualityRule("DQ_COLUMN_DISTINCT_COUNT", "字段去重值数量统计", QualityRuleDimension.UNIQUENESS, QualityRuleGranularity.COLUMN,
                        "统计字段去重后的取值数量，适用于唯一性和基数监控。",
                        "select count(distinct ${Column}) as distinct_count from ${Schema_Table}",
                        inputParams(
                                inputParam(1, "Column", QualityRuleParamType.COLUMN, "运行时替换为所选字段名"),
                                inputParam(2, "Schema_Table", QualityRuleParamType.TABLE, "运行时替换为模型物理表名")),
                        outputParams(outputParam(1, "distinct_count", QualityRuleOutputType.NUMBER, "字段去重后的取值数量"))),
                qualityRule("DQ_COLUMN_DUPLICATE_GROUP_COUNT", "字段重复分组数统计", QualityRuleDimension.UNIQUENESS, QualityRuleGranularity.COLUMN,
                        "统计字段值重复的分组数量，适用于主键、业务键唯一性校验。",
                        "select count(*) as duplicate_group_count from (select ${Column} from ${Schema_Table} group by ${Column} having count(*) > 1) dq",
                        inputParams(
                                inputParam(1, "Column", QualityRuleParamType.COLUMN, "运行时替换为所选字段名"),
                                inputParam(2, "Schema_Table", QualityRuleParamType.TABLE, "运行时替换为模型物理表名")),
                        outputParams(outputParam(1, "duplicate_group_count", QualityRuleOutputType.NUMBER, "出现重复值的分组数量"))),
                qualityRule("DQ_COMPOSITE_KEY_DUPLICATE_GROUP_COUNT", "组合键重复分组数统计", QualityRuleDimension.UNIQUENESS, QualityRuleGranularity.TABLE,
                        "统计组合键重复的分组数量，适用于多字段业务主键唯一性校验。",
                        "select count(*) as duplicate_group_count from (select ${KeyColumns} from ${Schema_Table} group by ${KeyColumns} having count(*) > 1) dq",
                        inputParams(
                                inputParam(1, "KeyColumns", QualityRuleParamType.CUSTOM, "组合键字段列表，例如 id,biz_date"),
                                inputParam(2, "Schema_Table", QualityRuleParamType.TABLE, "运行时替换为模型物理表名")),
                        outputParams(outputParam(1, "duplicate_group_count", QualityRuleOutputType.NUMBER, "组合键重复的分组数量"))),
                qualityRule("DQ_NUMERIC_RANGE_INVALID_COUNT", "数值范围异常数统计", QualityRuleDimension.VALIDITY, QualityRuleGranularity.COLUMN,
                        "统计数值字段不在指定上下限范围内的记录数。",
                        "select count(*) as invalid_count from ${Schema_Table} where ${Column} is not null and (${Column} < ${MinValue} or ${Column} > ${MaxValue})",
                        inputParams(
                                inputParam(1, "Schema_Table", QualityRuleParamType.TABLE, "运行时替换为模型物理表名"),
                                inputParam(2, "Column", QualityRuleParamType.COLUMN, "运行时替换为所选字段名"),
                                inputParam(3, "MinValue", QualityRuleParamType.CUSTOM, "数值下限"),
                                inputParam(4, "MaxValue", QualityRuleParamType.CUSTOM, "数值上限")),
                        outputParams(outputParam(1, "invalid_count", QualityRuleOutputType.NUMBER, "不在范围内的记录数"))),
                qualityRule("DQ_ENUM_INVALID_COUNT", "枚举值异常数统计", QualityRuleDimension.VALIDITY, QualityRuleGranularity.COLUMN,
                        "统计字段值不在允许枚举集合内的记录数。",
                        "select count(*) as invalid_count from ${Schema_Table} where ${Column} is not null and ${Column} not in (${AllowedValues})",
                        inputParams(
                                inputParam(1, "Schema_Table", QualityRuleParamType.TABLE, "运行时替换为模型物理表名"),
                                inputParam(2, "Column", QualityRuleParamType.COLUMN, "运行时替换为所选字段名"),
                                inputParam(3, "AllowedValues", QualityRuleParamType.CUSTOM, "SQL 枚举字面量列表，例如 'A','B','C'")),
                        outputParams(outputParam(1, "invalid_count", QualityRuleOutputType.NUMBER, "不在枚举集合内的记录数"))),
                qualityRule("DQ_STRING_LENGTH_INVALID_COUNT", "字符串长度异常数统计", QualityRuleDimension.VALIDITY, QualityRuleGranularity.COLUMN,
                        "统计字符串长度低于最小值或高于最大值的记录数。",
                        "select count(*) as invalid_count from ${Schema_Table} where ${Column} is not null and (length(${Column}) < ${MinLength} or length(${Column}) > ${MaxLength})",
                        inputParams(
                                inputParam(1, "Schema_Table", QualityRuleParamType.TABLE, "运行时替换为模型物理表名"),
                                inputParam(2, "Column", QualityRuleParamType.COLUMN, "运行时替换为所选字段名"),
                                inputParam(3, "MinLength", QualityRuleParamType.CUSTOM, "字符串最小长度"),
                                inputParam(4, "MaxLength", QualityRuleParamType.CUSTOM, "字符串最大长度")),
                        outputParams(outputParam(1, "invalid_count", QualityRuleOutputType.NUMBER, "长度不在范围内的记录数"))),
                qualityRule("DQ_LIKE_PATTERN_INVALID_COUNT", "LIKE 格式异常数统计", QualityRuleDimension.VALIDITY, QualityRuleGranularity.COLUMN,
                        "统计字段值不匹配指定 LIKE 模式的记录数。",
                        "select count(*) as invalid_count from ${Schema_Table} where ${Column} is not null and ${Column} not like ${LikePattern}",
                        inputParams(
                                inputParam(1, "Schema_Table", QualityRuleParamType.TABLE, "运行时替换为模型物理表名"),
                                inputParam(2, "Column", QualityRuleParamType.COLUMN, "运行时替换为所选字段名"),
                                inputParam(3, "LikePattern", QualityRuleParamType.CUSTOM, "SQL LIKE 模式，例如 '13%'")),
                        outputParams(outputParam(1, "invalid_count", QualityRuleOutputType.NUMBER, "不匹配 LIKE 模式的记录数"))),
                qualityRule("DQ_STALE_TIME_COUNT", "时间新鲜度过期数统计", QualityRuleDimension.TIMELINESS, QualityRuleGranularity.COLUMN,
                        "统计时间字段为空或早于新鲜度阈值的记录数。",
                        "select count(*) as stale_count from ${Schema_Table} where ${Column} is null or ${Column} < ${FreshnessThreshold}",
                        inputParams(
                                inputParam(1, "Schema_Table", QualityRuleParamType.TABLE, "运行时替换为模型物理表名"),
                                inputParam(2, "Column", QualityRuleParamType.COLUMN, "运行时替换为所选字段名"),
                                inputParam(3, "FreshnessThreshold", QualityRuleParamType.CUSTOM, "时间阈值，可填写动态函数表达式，例如 $getCurrentTime(\"yyyy-MM-dd HH:mm:ss\", \"-1d\")")),
                        outputParams(outputParam(1, "stale_count", QualityRuleOutputType.NUMBER, "时间为空或早于阈值的记录数"))),
                qualityRule("DQ_FUTURE_TIME_COUNT", "未来时间值数量统计", QualityRuleDimension.TIMELINESS, QualityRuleGranularity.COLUMN,
                        "统计时间字段晚于当前时间的记录数。",
                        "select count(*) as future_count from ${Schema_Table} where ${Column} > current_timestamp",
                        inputParams(
                                inputParam(1, "Schema_Table", QualityRuleParamType.TABLE, "运行时替换为模型物理表名"),
                                inputParam(2, "Column", QualityRuleParamType.COLUMN, "运行时替换为所选字段名")),
                        outputParams(outputParam(1, "future_count", QualityRuleOutputType.NUMBER, "晚于当前时间的记录数"))),
                qualityRule("DQ_FIELD_EQUALITY_INCONSISTENT_COUNT", "字段间取值不一致数统计", QualityRuleDimension.CONSISTENCY, QualityRuleGranularity.COLUMN,
                        "统计当前字段与同表对比字段取值不一致的记录数，包含单侧为空场景。",
                        "select count(*) as inconsistent_count from ${Schema_Table} where (${Column} <> ${CompareColumn}) or (${Column} is null and ${CompareColumn} is not null) or (${Column} is not null and ${CompareColumn} is null)",
                        inputParams(
                                inputParam(1, "Schema_Table", QualityRuleParamType.TABLE, "运行时替换为模型物理表名"),
                                inputParam(2, "Column", QualityRuleParamType.COLUMN, "运行时替换为所选字段名"),
                                inputParam(3, "CompareColumn", QualityRuleParamType.CUSTOM, "同表对比字段名，不加引号")),
                        outputParams(outputParam(1, "inconsistent_count", QualityRuleOutputType.NUMBER, "字段间取值不一致的记录数"))),
                qualityRule("DQ_FIELD_ORDER_INVALID_COUNT", "字段间顺序异常数统计", QualityRuleDimension.CONSISTENCY, QualityRuleGranularity.COLUMN,
                        "统计当前字段大于同表对比字段的记录数，适用于开始时间不应晚于结束时间等场景。",
                        "select count(*) as invalid_count from ${Schema_Table} where ${Column} is not null and ${CompareColumn} is not null and ${Column} > ${CompareColumn}",
                        inputParams(
                                inputParam(1, "Schema_Table", QualityRuleParamType.TABLE, "运行时替换为模型物理表名"),
                                inputParam(2, "Column", QualityRuleParamType.COLUMN, "运行时替换为所选字段名"),
                                inputParam(3, "CompareColumn", QualityRuleParamType.CUSTOM, "同表对比字段名，不加引号")),
                        outputParams(outputParam(1, "invalid_count", QualityRuleOutputType.NUMBER, "字段顺序异常的记录数"))),
                qualityRule("DQ_NEGATIVE_VALUE_COUNT", "负数值数量统计", QualityRuleDimension.ACCURACY, QualityRuleGranularity.COLUMN,
                        "统计小于 0 的数值记录数，适用于金额、数量等非负字段校验。",
                        "select count(*) as negative_count from ${Schema_Table} where ${Column} < 0",
                        inputParams(
                                inputParam(1, "Schema_Table", QualityRuleParamType.TABLE, "运行时替换为模型物理表名"),
                                inputParam(2, "Column", QualityRuleParamType.COLUMN, "运行时替换为所选字段名")),
                        outputParams(outputParam(1, "negative_count", QualityRuleOutputType.NUMBER, "小于 0 的记录数"))),
                qualityRule("DQ_ZERO_OR_NULL_VALUE_COUNT", "零值或空值数量统计", QualityRuleDimension.ACCURACY, QualityRuleGranularity.COLUMN,
                        "统计字段为空或等于 0 的记录数，适用于不应为空且不应为零的指标字段。",
                        "select count(*) as zero_or_null_count from ${Schema_Table} where ${Column} is null or ${Column} = 0",
                        inputParams(
                                inputParam(1, "Schema_Table", QualityRuleParamType.TABLE, "运行时替换为模型物理表名"),
                                inputParam(2, "Column", QualityRuleParamType.COLUMN, "运行时替换为所选字段名")),
                        outputParams(outputParam(1, "zero_or_null_count", QualityRuleOutputType.NUMBER, "为空或等于 0 的记录数")))
        );
    }

    private FieldMappingRuleSaveRequest fieldMappingRule(String mappingCode,
                                                         String mappingName,
                                                         String mappingType,
                                                         FieldMappingRuleParamSaveRequest... params) {
        FieldMappingRuleSaveRequest request = new FieldMappingRuleSaveRequest();
        request.setMappingCode(mappingCode);
        request.setMappingName(mappingName);
        request.setMappingType(mappingType);
        request.setEnabled(Boolean.TRUE);
        request.setDescription(BUILTIN_FIELD_MAPPING_DESCRIPTION);
        request.setParams(params == null ? new ArrayList<FieldMappingRuleParamSaveRequest>() : new ArrayList<FieldMappingRuleParamSaveRequest>(Arrays.asList(params)));
        return request;
    }

    private FieldMappingRuleParamSaveRequest fieldMappingParam(int order,
                                                               String name,
                                                               String componentType,
                                                               String paramValueJson,
                                                               String description) {
        FieldMappingRuleParamSaveRequest request = new FieldMappingRuleParamSaveRequest();
        request.setParamOrder(Integer.valueOf(order));
        request.setParamName(name);
        request.setComponentType(componentType);
        request.setParamValueJson(paramValueJson);
        request.setDescription(description);
        return request;
    }

    private QualityRuleSaveRequest qualityRule(String ruleCode,
                                               String ruleName,
                                               QualityRuleDimension dimension,
                                               QualityRuleGranularity granularity,
                                               String description,
                                               String logicSql,
                                               List<QualityRuleInputParamSaveRequest> inputParams,
                                               List<QualityRuleOutputParamSaveRequest> outputParams) {
        QualityRuleSaveRequest request = new QualityRuleSaveRequest();
        request.setRuleCode(ruleCode);
        request.setRuleName(ruleName);
        request.setScopeType(QualityRuleScopeType.SYSTEM);
        request.setRuleDimension(dimension);
        request.setGranularity(granularity);
        request.setDescription(description);
        request.setLogicSql(logicSql);
        request.setEnabled(Boolean.TRUE);
        request.setSupportedDatasourceTypes(new ArrayList<String>());
        request.setInputParams(new ArrayList<QualityRuleInputParamSaveRequest>(inputParams));
        request.setOutputParams(new ArrayList<QualityRuleOutputParamSaveRequest>(outputParams));
        return request;
    }

    private List<QualityRuleInputParamSaveRequest> inputParams(QualityRuleInputParamSaveRequest... params) {
        return params == null ? new ArrayList<QualityRuleInputParamSaveRequest>() : new ArrayList<QualityRuleInputParamSaveRequest>(Arrays.asList(params));
    }

    private List<QualityRuleOutputParamSaveRequest> outputParams(QualityRuleOutputParamSaveRequest... params) {
        return params == null ? new ArrayList<QualityRuleOutputParamSaveRequest>() : new ArrayList<QualityRuleOutputParamSaveRequest>(Arrays.asList(params));
    }

    private QualityRuleInputParamSaveRequest inputParam(int order,
                                                        String name,
                                                        QualityRuleParamType type,
                                                        String meaning) {
        QualityRuleInputParamSaveRequest request = new QualityRuleInputParamSaveRequest();
        request.setParamOrder(Integer.valueOf(order));
        request.setParamName(name);
        request.setParamType(type);
        request.setParamMeaning(meaning);
        return request;
    }

    private QualityRuleOutputParamSaveRequest outputParam(int order,
                                                          String field,
                                                          QualityRuleOutputType outputType,
                                                          String description) {
        QualityRuleOutputParamSaveRequest request = new QualityRuleOutputParamSaveRequest();
        request.setOutputOrder(Integer.valueOf(order));
        request.setResultField(field);
        request.setOutputType(outputType);
        request.setOutputDescription(description);
        return request;
    }

    private <T> List<T> safeList(List<T> items) {
        return items == null ? Collections.<T>emptyList() : items;
    }
}
