package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.enums.DataServiceParamPosition;
import com.jdragon.studio.dto.enums.DataServiceQueryOperator;
import com.jdragon.studio.dto.enums.DataServiceSourceType;
import com.jdragon.studio.dto.enums.DataServiceValueType;
import com.jdragon.studio.dto.model.DataServiceDefinitionView;
import com.jdragon.studio.dto.model.DataServicePublishParamView;
import com.jdragon.studio.dto.model.DataServiceRequestParamView;
import com.jdragon.studio.dto.model.DataServiceResponseParamView;
import com.jdragon.studio.dto.model.SqlExecutionResultView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class DataServiceInvocationSupport {

    private static final int DEFAULT_PAGE_NO = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 1000;
    private static final String OPEN_PATH_PREFIX = "/openapi/data-services";
    private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern HTTP_HEADER_NAME = Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]+");
    private static final Pattern TABLE_REFERENCE = Pattern.compile("[A-Za-z0-9_.$`\"-]+");

    InvocationPlan buildInvocationPlan(DataServiceDefinitionView service,
                                       Map<String, Object> headers,
                                       Map<String, Object> query,
                                       Map<String, Object> body) {
        int pageNum = DEFAULT_PAGE_NO;
        int pageSize = DEFAULT_PAGE_SIZE;
        List<Object> conditionParameters = new ArrayList<Object>();
        List<String> conditions = new ArrayList<String>();
        Map<String, DataServiceRequestParamView> requestParamMap = new LinkedHashMap<String, DataServiceRequestParamView>();
        for (DataServiceRequestParamView requestParam : service.getRequestParams()) {
            if (requestParam.getParamName() != null) {
                requestParamMap.put(requestParam.getParamName(), requestParam);
            }
        }
        for (DataServicePublishParamView publishParam : service.getPublishParams()) {
            if (publishParam == null || !hasText(publishParam.getFrontendParamName())) {
                continue;
            }
            DataServiceRequestParamView requestParam = requestParamMap.get(publishParam.getBackendParamName());
            Object rawValue = resolveIncomingValue(publishParam, headers, query, body);
            if (isPageParam(publishParam.getBackendParamName())) {
                if ("pageNum".equalsIgnoreCase(publishParam.getBackendParamName())) {
                    pageNum = normalizePageNo(toInteger(rawValue, DEFAULT_PAGE_NO));
                } else {
                    pageSize = normalizePageSize(toInteger(rawValue, DEFAULT_PAGE_SIZE));
                }
                continue;
            }
            if (isBlankValue(rawValue)) {
                if (Boolean.TRUE.equals(publishParam.getRequired())) {
                    throw new StudioException(StudioErrorCode.BAD_REQUEST, "Parameter is required: " + publishParam.getFrontendParamName());
                }
                continue;
            }
            if (requestParam == null || !hasText(requestParam.getFieldName())) {
                continue;
            }
            validateSimpleIdentifier(requestParam.getFieldName(), "Request field is invalid: " + requestParam.getFieldName());
            appendCondition(conditions, conditionParameters, requestParam, rawValue);
        }
        String selectSql = buildSelectSql(service);
        String whereSql = conditions.isEmpty() ? "" : " where " + join(conditions, " and ");
        int offset = (pageNum - 1) * pageSize;
        List<Object> dataParameters = new ArrayList<Object>(conditionParameters);
        dataParameters.add(Integer.valueOf(pageSize));
        dataParameters.add(Integer.valueOf(offset));
        InvocationPlan plan = new InvocationPlan();
        plan.pageNum = pageNum;
        plan.pageSize = pageSize;
        plan.dataSql = selectSql + whereSql + " limit ? offset ?";
        plan.countSql = "select count(*) as total_count from (" + selectSql + whereSql + ") ds_count";
        plan.dataParameters = dataParameters;
        plan.countParameters = new ArrayList<Object>(conditionParameters);
        return plan;
    }

    Map<String, Object> buildInvokeData(int pageNum,
                                        int pageSize,
                                        long total,
                                        List<Map<String, Object>> rows) {
        long pages = pageSize <= 0 ? 0 : (total + pageSize - 1) / pageSize;
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("pageNum", Integer.valueOf(pageNum));
        data.put("pageSize", Integer.valueOf(pageSize));
        data.put("pages", Long.valueOf(pages));
        Map<String, Object> table = new LinkedHashMap<String, Object>();
        table.put("bodies", rows == null ? new ArrayList<Map<String, Object>>() : rows);
        data.put("table", table);
        return data;
    }

    Map<String, Object> copyResponseData(Map<String, Object> input) {
        return input == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(input);
    }

    long extractTotal(SqlExecutionResultView result) {
        if (result == null || result.getRows() == null || result.getRows().isEmpty()) {
            return 0L;
        }
        Map<String, Object> row = result.getRows().get(0);
        if (row == null || row.isEmpty()) {
            return 0L;
        }
        Object value = row.values().iterator().next();
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            return 0L;
        }
    }

    DataServiceValueType resolveValueType(String fieldType) {
        if (fieldType == null) {
            return DataServiceValueType.STRING;
        }
        String normalized = fieldType.toLowerCase(Locale.ENGLISH);
        if (normalized.contains("int") || normalized.contains("long")) {
            return DataServiceValueType.INT;
        }
        if (normalized.contains("decimal") || normalized.contains("double") || normalized.contains("float") || normalized.contains("number")) {
            return DataServiceValueType.FLOAT;
        }
        if (normalized.contains("timestamp") || normalized.contains("datetime")) {
            return DataServiceValueType.TIMESTAMP;
        }
        if (normalized.contains("date") || normalized.contains("time")) {
            return DataServiceValueType.TIME;
        }
        return DataServiceValueType.STRING;
    }

    String defaultExampleValue(DataServiceValueType valueType) {
        if (valueType == DataServiceValueType.INT) {
            return "1";
        }
        if (valueType == DataServiceValueType.FLOAT) {
            return "1.0";
        }
        if (valueType == DataServiceValueType.TIME || valueType == DataServiceValueType.TIMESTAMP) {
            return "2026-04-16 12:00:00";
        }
        if (valueType == DataServiceValueType.LIST) {
            return "A,B";
        }
        return "示例";
    }

    String defaultValueFor(String paramName) {
        if ("pageNum".equalsIgnoreCase(paramName)) {
            return "1";
        }
        if ("pageSize".equalsIgnoreCase(paramName)) {
            return "10";
        }
        return null;
    }

    boolean isPageParam(String name) {
        return "pageNum".equalsIgnoreCase(name) || "pageSize".equalsIgnoreCase(name);
    }

    int normalizePageNo(Integer pageNo) {
        return pageNo == null || pageNo.intValue() < 1 ? DEFAULT_PAGE_NO : pageNo.intValue();
    }

    int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize.intValue() < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize.intValue(), MAX_PAGE_SIZE);
    }

    String buildEndpointPath(String serviceCode, String serviceKey) {
        return OPEN_PATH_PREFIX + "/" + serviceCode + "/" + serviceKey;
    }

    String buildCacheKey(Long serviceId, InvocationPlan plan) {
        return serviceId + "|" + plan.dataSql + "|" + plan.dataParameters;
    }

    String normalizeSelectSql(String sql) {
        String normalized = normalizeRequiredText(sql, "Custom SQL is required");
        String lower = normalized.toLowerCase(Locale.ENGLISH);
        if (!lower.startsWith("select")) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Only select SQL is supported");
        }
        if (containsSemicolonOutsideQuotes(normalized)) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "SQL must contain a single select statement");
        }
        return normalized;
    }

    void validateTableReference(String tableReference) {
        if (!hasText(tableReference) || !TABLE_REFERENCE.matcher(tableReference.trim()).matches()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "Table reference is invalid: " + tableReference);
        }
    }

    void validateSimpleIdentifier(String identifier, String message) {
        if (!hasText(identifier) || !SIMPLE_IDENTIFIER.matcher(identifier.trim()).matches()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
        }
    }

    void validateFrontendParamName(DataServicePublishParamView view) {
        String paramName = view.getFrontendParamName();
        if (view.getPosition() == DataServiceParamPosition.HEADER) {
            if (!hasText(paramName) || !HTTP_HEADER_NAME.matcher(paramName.trim()).matches()) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST, "Header parameter name is invalid: " + paramName);
            }
            return;
        }
        validateSimpleIdentifier(paramName, "Frontend parameter name is invalid: " + paramName);
    }

    String normalizeRequiredText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, message);
        }
        return value.trim();
    }

    String normalizeNullableText(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    String normalizeText(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    boolean isBlankValue(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String) {
            return ((String) value).trim().isEmpty();
        }
        return false;
    }

    long safeLong(Long value) {
        return value == null ? 0L : value.longValue();
    }

    String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.trim().isEmpty() ? null : text.trim();
    }

    Object firstPresent(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) {
                return map.get(key);
            }
        }
        return null;
    }

    <T extends Enum<T>> T enumValue(Class<T> enumClass, String value, T defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumClass, value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private String buildSelectSql(DataServiceDefinitionView service) {
        List<String> selectItems = new ArrayList<String>();
        for (DataServiceResponseParamView responseParam : service.getResponseParams()) {
            if (!Boolean.TRUE.equals(responseParam.getEnabled())) {
                continue;
            }
            String fieldName = normalizeRequiredText(responseParam.getFieldName(), "Response field is required");
            String paramName = hasText(responseParam.getParamName()) ? responseParam.getParamName().trim() : fieldName;
            validateSimpleIdentifier(fieldName, "Response field is invalid: " + fieldName);
            validateSimpleIdentifier(paramName, "Response param name is invalid: " + paramName);
            if (fieldName.equals(paramName)) {
                selectItems.add(fieldName);
            } else {
                selectItems.add(fieldName + " as " + paramName);
            }
        }
        if (selectItems.isEmpty()) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST, "At least one response field must be enabled");
        }
        String sourceSql;
        if (service.getSourceType() == DataServiceSourceType.TABLE) {
            String table = normalizeRequiredText(service.getModelPhysicalLocator(), "Model physical locator is empty");
            validateTableReference(table);
            sourceSql = table;
        } else {
            sourceSql = "(" + normalizeSelectSql(service.getCustomSql()) + ") ds";
        }
        return "select " + join(selectItems, ", ") + " from " + sourceSql;
    }

    private void appendCondition(List<String> conditions,
                                 List<Object> parameters,
                                 DataServiceRequestParamView requestParam,
                                 Object rawValue) {
        DataServiceQueryOperator operator = requestParam.getQueryOperator() == null
                ? DataServiceQueryOperator.EQ
                : requestParam.getQueryOperator();
        String fieldName = requestParam.getFieldName();
        if (operator == DataServiceQueryOperator.LIKE) {
            conditions.add(fieldName + " like ?");
            parameters.add("%" + String.valueOf(rawValue) + "%");
        } else if (operator == DataServiceQueryOperator.NE) {
            conditions.add(fieldName + " <> ?");
            parameters.add(convertValue(rawValue, requestParam.getValueType()));
        } else if (operator == DataServiceQueryOperator.GT) {
            conditions.add(fieldName + " > ?");
            parameters.add(convertValue(rawValue, requestParam.getValueType()));
        } else if (operator == DataServiceQueryOperator.GE) {
            conditions.add(fieldName + " >= ?");
            parameters.add(convertValue(rawValue, requestParam.getValueType()));
        } else if (operator == DataServiceQueryOperator.LT) {
            conditions.add(fieldName + " < ?");
            parameters.add(convertValue(rawValue, requestParam.getValueType()));
        } else if (operator == DataServiceQueryOperator.LE) {
            conditions.add(fieldName + " <= ?");
            parameters.add(convertValue(rawValue, requestParam.getValueType()));
        } else if (operator == DataServiceQueryOperator.CONTAINS || operator == DataServiceQueryOperator.NOT_CONTAINS) {
            List<Object> values = parseListValues(rawValue, requestParam.getValueType());
            if (values.isEmpty()) {
                return;
            }
            conditions.add(fieldName + (operator == DataServiceQueryOperator.NOT_CONTAINS ? " not in (" : " in (") + placeholders(values.size()) + ")");
            parameters.addAll(values);
        } else {
            if (requestParam.getValueType() == DataServiceValueType.LIST) {
                List<Object> values = parseListValues(rawValue, requestParam.getValueType());
                if (values.isEmpty()) {
                    return;
                }
                conditions.add(fieldName + " in (" + placeholders(values.size()) + ")");
                parameters.addAll(values);
            } else {
                conditions.add(fieldName + " = ?");
                parameters.add(convertValue(rawValue, requestParam.getValueType()));
            }
        }
    }

    private Object resolveIncomingValue(DataServicePublishParamView publishParam,
                                        Map<String, Object> headers,
                                        Map<String, Object> query,
                                        Map<String, Object> body) {
        DataServiceParamPosition position = publishParam.getPosition() == null ? DataServiceParamPosition.QUERY : publishParam.getPosition();
        Map<String, Object> source = position == DataServiceParamPosition.HEADER ? headers
                : position == DataServiceParamPosition.BODY ? body : query;
        Object value = lookupIgnoreCase(source, publishParam.getFrontendParamName());
        if (isBlankValue(value) && hasText(publishParam.getDefaultValue())) {
            return publishParam.getDefaultValue().trim();
        }
        return value;
    }

    private Object lookupIgnoreCase(Map<String, Object> source, String key) {
        if (source == null || key == null) {
            return null;
        }
        if (source.containsKey(key)) {
            return source.get(key);
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean containsSemicolonOutsideQuotes(String text) {
        boolean singleQuote = false;
        boolean doubleQuote = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\'' && !doubleQuote) {
                singleQuote = !singleQuote;
            } else if (current == '"' && !singleQuote) {
                doubleQuote = !doubleQuote;
            } else if (current == ';' && !singleQuote && !doubleQuote) {
                return true;
            }
        }
        return false;
    }

    private Object convertValue(Object value, DataServiceValueType valueType) {
        if (value == null) {
            return null;
        }
        DataServiceValueType type = valueType == null ? DataServiceValueType.STRING : valueType;
        if (type == DataServiceValueType.INT) {
            return Integer.valueOf(String.valueOf(value).trim());
        }
        if (type == DataServiceValueType.FLOAT) {
            return Double.valueOf(String.valueOf(value).trim());
        }
        return value;
    }

    private List<Object> parseListValues(Object rawValue, DataServiceValueType valueType) {
        List<Object> result = new ArrayList<Object>();
        if (rawValue == null) {
            return result;
        }
        if (rawValue instanceof Iterable) {
            for (Object item : (Iterable<?>) rawValue) {
                if (!isBlankValue(item)) {
                    result.add(convertValue(item, valueType == DataServiceValueType.LIST ? DataServiceValueType.STRING : valueType));
                }
            }
            return result;
        }
        if (rawValue.getClass().isArray()) {
            Object[] values = (Object[]) rawValue;
            for (Object item : values) {
                if (!isBlankValue(item)) {
                    result.add(convertValue(item, valueType == DataServiceValueType.LIST ? DataServiceValueType.STRING : valueType));
                }
            }
            return result;
        }
        String text = String.valueOf(rawValue);
        for (String item : text.split(",")) {
            if (hasText(item)) {
                result.add(item.trim());
            }
        }
        return result;
    }

    private String placeholders(int size) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < size; index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append("?");
        }
        return builder.toString();
    }

    private Integer toInteger(Object value, int defaultValue) {
        if (isBlankValue(value)) {
            return Integer.valueOf(defaultValue);
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (Exception ex) {
            return Integer.valueOf(defaultValue);
        }
    }

    private String join(List<String> values, String delimiter) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append(delimiter);
            }
            builder.append(values.get(index));
        }
        return builder.toString();
    }

    static final class InvocationPlan {
        int pageNum;
        int pageSize;
        String dataSql;
        String countSql;
        List<Object> dataParameters = new ArrayList<Object>();
        List<Object> countParameters = new ArrayList<Object>();
    }
}
