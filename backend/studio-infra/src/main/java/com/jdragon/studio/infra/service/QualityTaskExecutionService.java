package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.QualityRuleOutputType;
import com.jdragon.studio.dto.enums.QualityTaskAlertOperator;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.QualityTaskAlertConfig;
import com.jdragon.studio.dto.model.QualityTaskDefinitionView;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
public class QualityTaskExecutionService {

    private final DataSourceService dataSourceService;
    private final DataDevelopmentSqlExecutor sqlExecutor;
    private final QualityTaskExecutionPlanService executionPlanService;

    public QualityTaskExecutionService(DataSourceService dataSourceService,
                                       DataDevelopmentSqlExecutor sqlExecutor,
                                       QualityTaskExecutionPlanService executionPlanService) {
        this.dataSourceService = dataSourceService;
        this.sqlExecutor = sqlExecutor;
        this.executionPlanService = executionPlanService;
    }

    public Map<String, Object> execute(QualityTaskDefinitionView definition) {
        DataSourceDefinition datasource = requireSqlDatasource(definition.getDatasourceId());
        String resolvedSql = executionPlanService.buildResolvedSql(definition);
        SqlExecutionResultView sqlResult = sqlExecutor.executeSql(datasource, resolvedSql, null);
        List<Map<String, Object>> alertDetails = evaluateAlertDetails(definition.getAlertConfigs(), sqlResult.getRows());
        List<String> triggeredAlerts = collectAlertMessages(alertDetails);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", "SUCCESS");
        result.put("nodeType", "QUALITY_TASK");
        result.put("message", buildExecutionMessage(definition, sqlResult, triggeredAlerts));
        result.put("resolvedSql", resolvedSql);
        result.put("columns", sqlResult.getColumns());
        result.put("rows", sqlResult.getRows());
        result.put("summary", sqlResult.getSummary());
        result.put("alertCount", triggeredAlerts.size());
        result.put("alerts", triggeredAlerts);
        result.put("alertDetails", alertDetails);
        return result;
    }

    private DataSourceDefinition requireSqlDatasource(Long datasourceId) {
        DataSourceDefinition datasource = dataSourceService.getInternal(datasourceId);
        if (datasource == null) {
            throw new StudioException(StudioErrorCode.NOT_FOUND, "Datasource not found: " + datasourceId);
        }
        if (!sqlExecutor.supports(datasource)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Selected datasource does not support SQL quality checks");
        }
        return datasource;
    }

    private List<Map<String, Object>> evaluateAlertDetails(List<QualityTaskAlertConfig> alertConfigs, List<Map<String, Object>> rows) {
        List<Map<String, Object>> alertDetails = new ArrayList<Map<String, Object>>();
        if (alertConfigs == null || alertConfigs.isEmpty() || rows == null || rows.isEmpty()) {
            return alertDetails;
        }
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Map<String, Object> row = rows.get(rowIndex);
            for (QualityTaskAlertConfig alertConfig : alertConfigs) {
                if (alertConfig == null || !Boolean.TRUE.equals(alertConfig.getEnabled()) || !matchesAlert(alertConfig, row)) {
                    continue;
                }
                Map<String, Object> detail = buildAlertDetail(alertConfig, rowIndex, row);
                alertDetails.add(detail);
                log.warn(String.valueOf(detail.get("message")));
            }
        }
        return alertDetails;
    }

    private List<String> collectAlertMessages(List<Map<String, Object>> alertDetails) {
        List<String> triggeredAlerts = new ArrayList<String>();
        if (alertDetails == null || alertDetails.isEmpty()) {
            return triggeredAlerts;
        }
        for (Map<String, Object> alertDetail : alertDetails) {
            if (alertDetail != null && alertDetail.get("message") != null) {
                triggeredAlerts.add(String.valueOf(alertDetail.get("message")));
            }
        }
        return triggeredAlerts;
    }

    private Map<String, Object> buildAlertDetail(QualityTaskAlertConfig alertConfig,
                                                 int rowIndex,
                                                 Map<String, Object> row) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        Object actualValue = row == null ? null : row.get(alertConfig.getResultField());
        String message = String.format(Locale.ROOT,
                "Quality task alert triggered. row=%d, field=%s, operator=%s, value=%s",
                rowIndex + 1,
                alertConfig.getResultField(),
                alertConfig.getOperator(),
                actualValue);
        detail.put("rowIndex", Integer.valueOf(rowIndex + 1));
        detail.put("resultField", alertConfig.getResultField());
        detail.put("outputType", alertConfig.getOutputType() == null ? null : alertConfig.getOutputType().name());
        detail.put("operator", alertConfig.getOperator() == null ? null : alertConfig.getOperator().name());
        detail.put("actualValue", actualValue);
        detail.put("expectedValue", alertConfig.getExpectedValue());
        detail.put("minValue", alertConfig.getMinValue());
        detail.put("maxValue", alertConfig.getMaxValue());
        detail.put("message", message);
        return detail;
    }

    private boolean matchesAlert(QualityTaskAlertConfig alertConfig, Map<String, Object> row) {
        if (row == null || alertConfig == null || alertConfig.getOperator() == null || alertConfig.getResultField() == null) {
            return false;
        }
        Object actualValue = row.get(alertConfig.getResultField());
        if (alertConfig.getOutputType() == QualityRuleOutputType.NUMBER) {
            return compareNumber(alertConfig.getOperator(), actualValue, alertConfig.getExpectedValue(), alertConfig.getMinValue(), alertConfig.getMaxValue());
        }
        return compareString(alertConfig.getOperator(), actualValue, alertConfig.getExpectedValue());
    }

    private boolean compareString(QualityTaskAlertOperator operator, Object actualValue, String expectedValue) {
        String actual = actualValue == null ? null : String.valueOf(actualValue);
        String expected = expectedValue == null ? null : expectedValue;
        if (operator == QualityTaskAlertOperator.EQ) {
            return equalsNullable(actual, expected);
        }
        if (operator == QualityTaskAlertOperator.NE) {
            return !equalsNullable(actual, expected);
        }
        return false;
    }

    private boolean compareNumber(QualityTaskAlertOperator operator,
                                  Object actualValue,
                                  String expectedValue,
                                  String minValue,
                                  String maxValue) {
        BigDecimal actual = toDecimal(actualValue);
        if (actual == null || operator == null) {
            return false;
        }
        switch (operator) {
            case EQ:
                return hasComparable(actual, expectedValue) && compare(actual, expectedValue) == 0;
            case NE:
                return hasComparable(actual, expectedValue) && compare(actual, expectedValue) != 0;
            case LT:
                return hasComparable(actual, expectedValue) && compare(actual, expectedValue) < 0;
            case LE:
                return hasComparable(actual, expectedValue) && compare(actual, expectedValue) <= 0;
            case GT:
                return hasComparable(actual, expectedValue) && compare(actual, expectedValue) > 0;
            case GE:
                return hasComparable(actual, expectedValue) && compare(actual, expectedValue) >= 0;
            case LT_R_LT:
                return hasComparable(actual, minValue) && hasComparable(actual, maxValue)
                        && compare(actual, minValue) > 0 && compare(actual, maxValue) < 0;
            case LT_R_LE:
                return hasComparable(actual, minValue) && hasComparable(actual, maxValue)
                        && compare(actual, minValue) > 0 && compare(actual, maxValue) <= 0;
            case LE_R_LT:
                return hasComparable(actual, minValue) && hasComparable(actual, maxValue)
                        && compare(actual, minValue) >= 0 && compare(actual, maxValue) < 0;
            case LE_R_LE:
                return hasComparable(actual, minValue) && hasComparable(actual, maxValue)
                        && compare(actual, minValue) >= 0 && compare(actual, maxValue) <= 0;
            default:
                return false;
        }
    }

    private boolean hasComparable(BigDecimal actual, String expectedValue) {
        return actual != null && toDecimal(expectedValue) != null;
    }

    private int compare(BigDecimal actual, String expectedValue) {
        BigDecimal expected = toDecimal(expectedValue);
        if (expected == null) {
            return Integer.MIN_VALUE;
        }
        return actual.compareTo(expected);
    }

    private BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(String.valueOf(value));
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean equalsNullable(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private String buildExecutionMessage(QualityTaskDefinitionView definition,
                                         SqlExecutionResultView sqlResult,
                                         List<String> triggeredAlerts) {
        String taskName = definition == null ? null : definition.getTaskName();
        if (triggeredAlerts.isEmpty()) {
            return String.format("%s executed successfully, returned %d row(s)",
                    taskName == null ? "Quality task" : taskName,
                    sqlResult.getRows() == null ? 0 : sqlResult.getRows().size());
        }
        return String.format("%s executed successfully, %d alert(s) triggered",
                taskName == null ? "Quality task" : taskName,
                triggeredAlerts.size());
    }
}
