package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.QualityRuleGranularity;
import com.jdragon.studio.dto.enums.QualityRuleOutputType;
import com.jdragon.studio.dto.enums.QualityRuleParamType;
import com.jdragon.studio.dto.model.QualityRuleInputParamView;
import com.jdragon.studio.dto.model.QualityRuleOutputParamView;
import com.jdragon.studio.dto.model.QualityRuleParseResultView;
import com.jdragon.studio.dto.model.QualityRuleValidationResultView;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QualitySqlTemplateService {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern FUNCTION_PATTERN = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)\\(([^()]*)\\)");
    private static final Pattern ALIAS_PATTERN = Pattern.compile("(?i)\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*$");

    private final QualityDynamicFunctionService dynamicFunctionService;
    private final DataDevelopmentSqlExecutor sqlExecutor;

    public QualitySqlTemplateService(QualityDynamicFunctionService dynamicFunctionService,
                                     DataDevelopmentSqlExecutor sqlExecutor) {
        this.dynamicFunctionService = dynamicFunctionService;
        this.sqlExecutor = sqlExecutor;
    }

    public Set<String> supportedDatasourceTypes() {
        return sqlExecutor.supportedDatasourceTypes();
    }

    public QualityRuleParseResultView parseRule(QualityRuleGranularity granularity, String logicSql) {
        QualityRuleParseResultView result = new QualityRuleParseResultView();
        validateTemplateStructure(granularity, logicSql, result.getWarnings());
        for (PlaceholderItem placeholder : collectPlaceholders(logicSql)) {
            QualityRuleInputParamView item = new QualityRuleInputParamView();
            item.setParamOrder(Integer.valueOf(result.getInputParams().size() + 1));
            item.setParamName(placeholder.getPlaceholderName());
            item.setParamType(resolveParamType(placeholder.getPlaceholderName()));
            item.setParamMeaning(resolveParamMeaning(placeholder.getPlaceholderName()));
            result.getInputParams().add(item);
        }
        result.getOutputParams().addAll(extractStaticOutputParams(granularity, logicSql, result.getWarnings()));
        return result;
    }

    public QualityRuleValidationResultView validateRule(QualityRuleGranularity granularity, String logicSql) {
        QualityRuleValidationResultView result = new QualityRuleValidationResultView();
        List<String> warnings = result.getWarnings();
        try {
            validateTemplateStructure(granularity, logicSql, warnings);
            parseSelectStatement(normalizeForParse(logicSql), true);
            result.setValid(Boolean.TRUE);
            result.setMessage("SQL 语义校验通过");
        } catch (Exception ex) {
            result.setValid(Boolean.FALSE);
            result.setMessage(ex.getMessage());
        }
        return result;
    }

    public List<QualityRuleOutputParamView> resolveOutputParamsFromResult(List<String> columns, List<Map<String, Object>> rows) {
        List<QualityRuleOutputParamView> outputParams = new ArrayList<QualityRuleOutputParamView>();
        List<Map<String, Object>> safeRows = rows == null ? new ArrayList<Map<String, Object>>() : rows;
        for (int index = 0; columns != null && index < columns.size(); index++) {
            String column = columns.get(index);
            QualityRuleOutputParamView item = new QualityRuleOutputParamView();
            item.setOutputOrder(Integer.valueOf(index + 1));
            item.setResultField(column);
            item.setOutputType(resolveOutputType(column, safeRows));
            item.setOutputDescription(column);
            outputParams.add(item);
        }
        return outputParams;
    }

    public String resolveSql(String logicSql,
                             Map<String, String> bindings,
                             String whereClause) {
        String resolvedLogic = replaceFunctions(logicSql);
        Map<String, String> safeBindings = new LinkedHashMap<String, String>();
        if (bindings != null) {
            for (Map.Entry<String, String> entry : bindings.entrySet()) {
                safeBindings.put(entry.getKey(), replaceFunctions(entry.getValue()));
            }
        }
        String substituted = replacePlaceholders(resolvedLogic, safeBindings);
        String resolvedWhere = replaceFunctions(whereClause);
        if (resolvedWhere == null || resolvedWhere.trim().isEmpty()) {
            return substituted;
        }
        return appendWhereClause(substituted, resolvedWhere.trim());
    }

    public Map<String, String> buildRuntimeBindings(String schemaTable,
                                                    String columnName,
                                                    List<com.jdragon.studio.dto.model.QualityTaskParamBinding> bindings) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("Schema_Table", schemaTable);
        if (columnName != null && !columnName.trim().isEmpty()) {
            values.put("Column", columnName.trim());
        }
        if (bindings != null) {
            for (com.jdragon.studio.dto.model.QualityTaskParamBinding binding : bindings) {
                if (binding == null || binding.getParamName() == null) {
                    continue;
                }
                values.put(binding.getParamName(), binding.getParamValue());
            }
        }
        return values;
    }

    private void validateTemplateStructure(QualityRuleGranularity granularity,
                                           String logicSql,
                                           List<String> warnings) {
        if (logicSql == null || logicSql.trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "定义逻辑不能为空");
        }
        ensurePlaceholderStructure(logicSql);
        List<String> functionErrors = dynamicFunctionService.validate(logicSql);
        if (!functionErrors.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, functionErrors.get(0));
        }
        Set<String> placeholders = new LinkedHashSet<String>();
        for (PlaceholderItem item : collectPlaceholders(logicSql)) {
            placeholders.add(item.getPlaceholderName());
        }
        if (granularity == QualityRuleGranularity.TABLE && placeholders.contains("Column")) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "表级规则不允许引用 ${Column}");
        }
        if (warnings != null && extractStaticOutputParams(granularity, logicSql, new ArrayList<String>()).isEmpty()) {
            warnings.add("静态解析未识别到输出字段，建议在 SELECT 子句中显式使用 AS 别名。");
        }
    }

    private void ensurePlaceholderStructure(String logicSql) {
        int openIndex = logicSql.indexOf("${");
        while (openIndex >= 0) {
            int closeIndex = logicSql.indexOf('}', openIndex);
            if (closeIndex < 0) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "存在未闭合的占位符");
            }
            openIndex = logicSql.indexOf("${", closeIndex);
        }
    }

    private List<QualityRuleOutputParamView> extractStaticOutputParams(QualityRuleGranularity granularity,
                                                                       String logicSql,
                                                                       List<String> warnings) {
        List<QualityRuleOutputParamView> outputParams = new ArrayList<QualityRuleOutputParamView>();
        try {
            Select select = parseSelectStatement(normalizeForParse(logicSql), false);
            if (!(select.getSelectBody() instanceof PlainSelect)) {
                if (warnings != null) {
                    warnings.add("当前 SQL 结构较复杂，静态结果字段解析将在任务阶段连库完成。");
                }
                return outputParams;
            }
            PlainSelect plainSelect = (PlainSelect) select.getSelectBody();
            List<SelectItem<?>> items = plainSelect.getSelectItems();
            for (SelectItem<?> item : items) {
                if (isWildcardItem(item)) {
                    if (warnings != null) {
                        warnings.add("检测到 * 通配符，输出字段需在任务阶段连库解析。");
                    }
                    continue;
                }
                String label = resolveSelectItemLabel(item);
                if (label == null || label.trim().isEmpty()) {
                    continue;
                }
                QualityRuleOutputParamView outputParam = new QualityRuleOutputParamView();
                outputParam.setOutputOrder(Integer.valueOf(outputParams.size() + 1));
                outputParam.setResultField(label.trim());
                outputParam.setOutputType(QualityRuleOutputType.STRING);
                outputParam.setOutputDescription(label.trim());
                outputParams.add(outputParam);
            }
        } catch (Exception ex) {
            if (warnings != null) {
                warnings.add("静态结果字段解析失败，将在任务阶段连库解析: " + ex.getMessage());
            }
        }
        return outputParams;
    }

    private String resolveSelectItemLabel(SelectItem<?> item) {
        if (item == null) {
            return null;
        }
        String text = item.toString();
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String trimmed = text.trim();
        Matcher aliasMatcher = ALIAS_PATTERN.matcher(trimmed);
        if (aliasMatcher.find()) {
            return aliasMatcher.group(1);
        }
        int lastSpaceIndex = trimmed.lastIndexOf(' ');
        if (lastSpaceIndex > 0 && lastSpaceIndex < trimmed.length() - 1) {
            String candidate = trimmed.substring(lastSpaceIndex + 1).trim();
            if (candidate.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                return candidate;
            }
        }
        return trimmed;
    }

    private boolean isWildcardItem(SelectItem<?> item) {
        if (item == null) {
            return false;
        }
        String text = item.toString();
        if (text == null) {
            return false;
        }
        String normalized = text.trim();
        return "*".equals(normalized) || normalized.endsWith(".*");
    }

    private Select parseSelectStatement(String sql, boolean strictQueryOnly) throws JSQLParserException {
        Statement statement = CCJSqlParserUtil.parse(sql);
        if (!(statement instanceof Select)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "定义逻辑仅支持 SELECT 查询语句");
        }
        if (strictQueryOnly) {
            return (Select) statement;
        }
        return (Select) statement;
    }

    private String normalizeForParse(String logicSql) {
        String normalized = logicSql == null ? "" : logicSql;
        normalized = FUNCTION_PATTERN.matcher(normalized).replaceAll("0");
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(normalized);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String placeholderName = matcher.group(1) == null ? "" : matcher.group(1).trim();
            String replacement;
            if ("Schema_Table".equals(placeholderName)) {
                replacement = "quality_schema_table";
            } else if ("Column".equals(placeholderName)) {
                replacement = "quality_column";
            } else {
                replacement = "0";
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private List<PlaceholderItem> collectPlaceholders(String logicSql) {
        List<PlaceholderItem> placeholders = new ArrayList<PlaceholderItem>();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(logicSql == null ? "" : logicSql);
        Set<String> uniqueNames = new LinkedHashSet<String>();
        while (matcher.find()) {
            String placeholderName = matcher.group(1) == null ? "" : matcher.group(1).trim();
            if (placeholderName.isEmpty() || !uniqueNames.add(placeholderName)) {
                continue;
            }
            placeholders.add(new PlaceholderItem(placeholderName));
        }
        return placeholders;
    }

    private QualityRuleParamType resolveParamType(String placeholderName) {
        if ("Schema_Table".equals(placeholderName)) {
            return QualityRuleParamType.TABLE;
        }
        if ("Column".equals(placeholderName)) {
            return QualityRuleParamType.COLUMN;
        }
        return QualityRuleParamType.CUSTOM;
    }

    private String resolveParamMeaning(String placeholderName) {
        if ("Schema_Table".equals(placeholderName)) {
            return "运行时替换为模型物理表名";
        }
        if ("Column".equals(placeholderName)) {
            return "运行时替换为所选字段名";
        }
        return "自定义输入参数";
    }

    private QualityRuleOutputType resolveOutputType(String column,
                                                    List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            Object value = row.get(column);
            if (value instanceof Number) {
                return QualityRuleOutputType.NUMBER;
            }
            if (value != null) {
                return QualityRuleOutputType.STRING;
            }
        }
        return QualityRuleOutputType.STRING;
    }

    private String replaceFunctions(String value) {
        if (value == null || value.trim().isEmpty()) {
            return value;
        }
        return dynamicFunctionService.replaceAll(value);
    }

    private String replacePlaceholders(String value, Map<String, String> bindings) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(value == null ? "" : value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1) == null ? "" : matcher.group(1).trim();
            String replacement = bindings != null && bindings.containsKey(key) ? bindings.get(key) : matcher.group();
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement == null ? "" : replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String appendWhereClause(String sql, String whereClause) {
        try {
            Select select = parseSelectStatement(sql, true);
            if (select.getSelectBody() instanceof PlainSelect) {
                PlainSelect plainSelect = (PlainSelect) select.getSelectBody();
                Expression appendedWhere = CCJSqlParserUtil.parseCondExpression(whereClause);
                if (plainSelect.getWhere() == null) {
                    plainSelect.setWhere(appendedWhere);
                } else {
                    plainSelect.setWhere(new AndExpression(plainSelect.getWhere(), appendedWhere));
                }
                return select.toString();
            }
        } catch (Exception ignored) {
        }
        return "select * from (" + sql + ") quality_task_result where " + whereClause;
    }

    private static final class PlaceholderItem {
        private final String placeholderName;

        private PlaceholderItem(String placeholderName) {
            this.placeholderName = placeholderName;
        }

        public String getPlaceholderName() {
            return placeholderName;
        }
    }
}
