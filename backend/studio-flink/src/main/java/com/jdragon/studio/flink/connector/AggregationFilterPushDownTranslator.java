package com.jdragon.studio.flink.connector;

import org.apache.flink.table.expressions.CallExpression;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.expressions.NestedFieldReferenceExpression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.expressions.ValueLiteralExpression;
import org.apache.flink.table.functions.BuiltInFunctionDefinition;
import org.apache.flink.table.functions.BuiltInFunctionDefinitions;
import org.apache.flink.table.functions.FunctionDefinition;

import java.math.BigDecimal;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AggregationFilterPushDownTranslator {
    private static final Pattern SERIALIZABLE_BINARY_FUNCTION = Pattern.compile(
            "^(equals|greaterThan|greaterThanOrEqual|lessThan|lessThanOrEqual|=|>|>=|<|<=)\\((.+?),\\s*(.+)\\)$",
            Pattern.CASE_INSENSITIVE);

    private AggregationFilterPushDownTranslator() {
    }

    static Translation translate(List<ResolvedExpression> filters,
                                 AggregationFlinkTableRuntime runtime,
                                 AggregationPluginKind pluginKind) {
        FilePathPushdownConfig pathConfig = FilePathPushdownConfig.from(runtime.getModelMetadata());
        HttpPushdownMappingConfig httpConfig = HttpPushdownMappingConfig.from(
                runtime.getModelMetadata(), runtime.getPhysicalLocator());
        List<ResolvedExpression> accepted = new ArrayList<ResolvedExpression>();
        List<ResolvedExpression> remaining = new ArrayList<ResolvedExpression>();
        List<String> pushedSql = new ArrayList<String>();
        List<String> remainingSql = new ArrayList<String>();
        List<FilePathPushdownFilter> pathFilters = new ArrayList<FilePathPushdownFilter>();
        List<Map<String, Object>> httpPushdownFilters = new ArrayList<Map<String, Object>>();
        Map<String, Object> assignedHttpTargets = new java.util.LinkedHashMap<String, Object>();
        boolean httpFilterAlwaysFalse = false;
        if (filters == null) {
            return new Translation(accepted, remaining, pushedSql, remainingSql, pathFilters,
                    httpPushdownFilters, false);
        }
        for (ResolvedExpression filter : filters) {
            for (ResolvedExpression conjunct : flattenConjuncts(filter)) {
                Predicate predicate = toPredicate(conjunct);
                if (predicate == null) {
                    if (pluginKind == AggregationPluginKind.HTTP) {
                        assertHttpResidualFilterSafe(httpConfig, runtime, conjunct);
                    }
                    remaining.add(conjunct);
                    remainingSql.add(serializable(conjunct));
                    continue;
                }
                if (pluginKind == AggregationPluginKind.STRUCTURED && predicate.hasNoPathFields(pathConfig)) {
                    accepted.add(conjunct);
                    pushedSql.add(predicate.getSql());
                    continue;
                }
                if (pluginKind == AggregationPluginKind.FILE && predicate.hasOnlyPathFields(pathConfig)
                        && predicate.supportsPathDate()) {
                    accepted.add(conjunct);
                    pushedSql.add(predicate.getSql());
                    pathFilters.addAll(predicate.toPathFilters(pathConfig, conjunct));
                    continue;
                }
                if (pluginKind == AggregationPluginKind.HTTP) {
                    HttpPushdownDecision decision = predicate.toHttpPushdown(httpConfig, conjunct);
                    if (decision.isAccepted()) {
                        HttpBodyPushdownValidator.validate(runtime, decision.getPushdownFilters());
                        httpFilterAlwaysFalse = httpFilterAlwaysFalse || decision.isAlwaysFalse()
                                || reconcileHttpTargets(assignedHttpTargets, decision.getPushdownFilters());
                        accepted.add(conjunct);
                        pushedSql.add(predicate.getSql());
                        httpPushdownFilters.addAll(decision.getPushdownFilters());
                        continue;
                    }
                    assertHttpResidualFilterSafe(httpConfig, runtime, conjunct);
                    remaining.add(conjunct);
                    remainingSql.add(serializable(conjunct));
                    continue;
                }
                remaining.add(conjunct);
                remainingSql.add(serializable(conjunct));
            }
        }
        return new Translation(accepted, remaining, pushedSql, remainingSql, pathFilters,
                httpPushdownFilters, httpFilterAlwaysFalse);
    }

    private static boolean reconcileHttpTargets(Map<String, Object> assignedTargets,
                                                List<Map<String, Object>> filters) {
        boolean conflicting = false;
        if (filters == null) {
            return false;
        }
        java.util.Iterator<Map<String, Object>> iterator = filters.iterator();
        while (iterator.hasNext()) {
            Map<String, Object> filter = iterator.next();
            String target = httpTargetIdentity(filter);
            Object value = singleHttpValue(filter);
            if (assignedTargets.containsKey(target)) {
                if (!httpValuesEquivalent(assignedTargets.get(target), value)) {
                    conflicting = true;
                } else {
                    iterator.remove();
                }
                continue;
            }
            assignedTargets.put(target, value);
        }
        return conflicting;
    }

    private static boolean httpValuesEquivalent(Object left, Object right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof Number && right instanceof Number) {
            try {
                return new BigDecimal(String.valueOf(left)).compareTo(new BigDecimal(String.valueOf(right))) == 0;
            } catch (NumberFormatException ignored) {
                // NaN and infinity fall back to their Java value semantics.
            }
        }
        return java.util.Objects.deepEquals(left, right);
    }

    private static String httpTargetIdentity(Map<String, Object> filter) {
        String location = HttpPushdownMappingConfig.normalizeLocation(stringValue(filter.get("location")));
        if ("param".equals(location) || "query".equals(location)) {
            return "query." + stringValue(filter.get("requestParamName"));
        }
        if ("header".equals(location)) {
            return "header." + stringValue(filter.get("headerName")).toLowerCase(Locale.ENGLISH);
        }
        if ("body".equals(location)) {
            return "body." + stringValue(filter.get("bodyPath"));
        }
        if ("path".equals(location)) {
            return "path." + stringValue(filter.get("pathVariable"));
        }
        return location + "." + stringValue(filter.get("resultField"));
    }

    private static Object singleHttpValue(Map<String, Object> filter) {
        Object values = filter == null ? null : filter.get("values");
        if (!(values instanceof List<?>) || ((List<?>) values).isEmpty()) {
            return null;
        }
        return ((List<?>) values).get(0);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static void assertHttpResidualFilterSafe(HttpPushdownMappingConfig config,
                                                     AggregationFlinkTableRuntime runtime,
                                                     ResolvedExpression expression) {
        Set<String> requestFields = new LinkedHashSet<String>();
        boolean containsVirtualOnlyField = false;
        for (String field : referencedFields(expression)) {
            HttpFieldRef fieldRef = HttpFieldRef.parse(field);
            if (fieldRef.explicitLocation != null) {
                requestFields.add(fieldRef.explicitLocation + "." + fieldRef.field);
                continue;
            }
            if (!isPhysicalHttpResultField(runtime, fieldRef.field)
                    && config != null
                    && !config.findByField(fieldRef.field).isEmpty()) {
                requestFields.add(fieldRef.field);
                containsVirtualOnlyField = true;
            }
        }
        if (!requestFields.isEmpty()) {
            String virtualFieldHint = containsVirtualOnlyField
                    ? "；无前缀字段仅存在于 ReaderOptions，不能作为响应结果残留过滤"
                    : "";
            throw new IllegalArgumentException("HTTP 请求字段 " + String.join(", ", requestFields)
                    + " 使用了无法下推的表达式 " + serializable(expression)
                    + virtualFieldHint + "；模型当前仅支持字段与字面量之间的 = 条件");
        }
    }

    private static boolean isPhysicalHttpResultField(AggregationFlinkTableRuntime runtime, String field) {
        if (runtime == null || runtime.getModelMetadata() == null || field == null) {
            return false;
        }
        Object columns = runtime.getModelMetadata().get("columns");
        if (!(columns instanceof List<?>)) {
            return false;
        }
        for (Object item : (List<?>) columns) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> column = (Map<?, ?>) item;
            String name = stringValue(column.get("name"));
            if (name.isEmpty()) {
                name = stringValue(column.get("columnName"));
            }
            if (field.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> referencedFields(ResolvedExpression expression) {
        Set<String> result = new LinkedHashSet<String>();
        collectReferencedFields(expression, result);
        return result;
    }

    private static void collectReferencedFields(ResolvedExpression expression, Set<String> result) {
        if (expression == null) {
            return;
        }
        String field = fieldName(expression);
        if (field != null) {
            result.add(field);
            return;
        }
        if (expression instanceof CallExpression) {
            for (ResolvedExpression child : ((CallExpression) expression).getResolvedChildren()) {
                collectReferencedFields(child, result);
            }
        }
    }

    private static List<ResolvedExpression> flattenConjuncts(ResolvedExpression expression) {
        List<ResolvedExpression> result = new ArrayList<ResolvedExpression>();
        collectConjuncts(expression, result);
        return result;
    }

    private static void collectConjuncts(ResolvedExpression expression, List<ResolvedExpression> result) {
        if (expression instanceof CallExpression) {
            CallExpression call = (CallExpression) expression;
            if (isFunction(call.getFunctionDefinition(), BuiltInFunctionDefinitions.AND, "AND")
                    && call.getResolvedChildren().size() == 2) {
                collectConjuncts(call.getResolvedChildren().get(0), result);
                collectConjuncts(call.getResolvedChildren().get(1), result);
                return;
            }
        }
        result.add(expression);
    }

    private static Predicate toPredicate(ResolvedExpression expression) {
        if (!(expression instanceof CallExpression)) {
            return null;
        }
        CallExpression call = (CallExpression) expression;
        FunctionDefinition function = call.getFunctionDefinition();
        List<ResolvedExpression> children = call.getResolvedChildren();
        if (isFunction(function, BuiltInFunctionDefinitions.EQUALS, "EQUALS", "=")) {
            return firstResolved(binary(children, "="), expression);
        }
        if (isFunction(function, BuiltInFunctionDefinitions.GREATER_THAN, "GREATER_THAN", ">")) {
            return firstResolved(binary(children, ">"), expression);
        }
        if (isFunction(function, BuiltInFunctionDefinitions.GREATER_THAN_OR_EQUAL,
                "GREATER_THAN_OR_EQUAL", ">=")) {
            return firstResolved(binary(children, ">="), expression);
        }
        if (isFunction(function, BuiltInFunctionDefinitions.LESS_THAN, "LESS_THAN", "<")) {
            return firstResolved(binary(children, "<"), expression);
        }
        if (isFunction(function, BuiltInFunctionDefinitions.LESS_THAN_OR_EQUAL, "LESS_THAN_OR_EQUAL", "<=")) {
            return firstResolved(binary(children, "<="), expression);
        }
        if (isFunction(function, BuiltInFunctionDefinitions.IS_NULL, "IS_NULL") && children.size() == 1) {
            String field = fieldName(children.get(0));
            return field == null ? null : Predicate.of(field, "IS NULL", new ArrayList<Object>(), field + " IS NULL");
        }
        if (isFunction(function, BuiltInFunctionDefinitions.IS_NOT_NULL, "IS_NOT_NULL") && children.size() == 1) {
            String field = fieldName(children.get(0));
            return field == null ? null : Predicate.of(field, "IS NOT NULL", new ArrayList<Object>(), field + " IS NOT NULL");
        }
        if (isFunction(function, BuiltInFunctionDefinitions.BETWEEN, "BETWEEN") && children.size() == 3) {
            String field = fieldName(children.get(0));
            Object lower = literal(children.get(1));
            Object upper = literal(children.get(2));
            if (field == null || lower == UnsupportedLiteral.INSTANCE || upper == UnsupportedLiteral.INSTANCE) {
                return null;
            }
            return Predicate.of(field, "BETWEEN", Arrays.asList(lower, upper),
                    field + " BETWEEN " + sqlLiteral(lower) + " AND " + sqlLiteral(upper));
        }
        if (isFunction(function, BuiltInFunctionDefinitions.IN, "IN") && children.size() >= 2) {
            String field = fieldName(children.get(0));
            if (field == null) {
                return null;
            }
            List<Object> values = new ArrayList<Object>();
            List<String> sqlValues = new ArrayList<String>();
            for (int i = 1; i < children.size(); i++) {
                Object value = literal(children.get(i));
                if (value == UnsupportedLiteral.INSTANCE) {
                    return null;
                }
                values.add(value);
                sqlValues.add(sqlLiteral(value));
            }
            return Predicate.of(field, "IN", values, field + " IN (" + String.join(", ", sqlValues) + ")");
        }
        return serializableBinary(expression);
    }

    private static Predicate firstResolved(Predicate predicate, ResolvedExpression expression) {
        return predicate == null ? serializableBinary(expression) : predicate;
    }

    private static boolean isFunction(FunctionDefinition function, FunctionDefinition expected, String... names) {
        if (function == expected) {
            return true;
        }
        Set<String> candidates = new LinkedHashSet<String>();
        if (function != null) {
            candidates.add(String.valueOf(function));
            if (function instanceof BuiltInFunctionDefinition) {
                BuiltInFunctionDefinition builtIn = (BuiltInFunctionDefinition) function;
                candidates.add(builtIn.getName());
                candidates.add(builtIn.getSqlName());
                candidates.add(builtIn.getQualifiedName());
            }
        }
        for (String candidate : candidates) {
            for (String name : names) {
                if (sameFunctionName(candidate, name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean sameFunctionName(String candidate, String expected) {
        if (candidate == null || expected == null) {
            return false;
        }
        String left = candidate.trim().toUpperCase(Locale.ENGLISH);
        String right = expected.trim().toUpperCase(Locale.ENGLISH);
        if (left.equals(right)) {
            return true;
        }
        String compactLeft = left.replaceAll("[^A-Z0-9]+", "");
        String compactRight = right.replaceAll("[^A-Z0-9]+", "");
        return !compactRight.isEmpty() && compactLeft.equals(compactRight);
    }

    private static Predicate binary(List<ResolvedExpression> children, String operator) {
        if (children.size() != 2) {
            return null;
        }
        String leftField = fieldName(children.get(0));
        Object rightLiteral = literal(children.get(1));
        if (leftField != null && rightLiteral != UnsupportedLiteral.INSTANCE) {
            return Predicate.of(leftField, operator, Arrays.asList(rightLiteral),
                    leftField + " " + operator + " " + sqlLiteral(rightLiteral));
        }
        String rightField = fieldName(children.get(1));
        Object leftLiteral = literal(children.get(0));
        if (rightField != null && leftLiteral != UnsupportedLiteral.INSTANCE) {
            String flipped = flip(operator);
            return Predicate.of(rightField, flipped, Arrays.asList(leftLiteral),
                    rightField + " " + flipped + " " + sqlLiteral(leftLiteral));
        }
        return null;
    }

    private static Predicate serializableBinary(ResolvedExpression expression) {
        String text = serializable(expression);
        Matcher matcher = SERIALIZABLE_BINARY_FUNCTION.matcher(text.trim());
        if (!matcher.matches()) {
            return null;
        }
        String operator = serializableOperator(matcher.group(1));
        String field = serializableField(matcher.group(2));
        Object value = serializableLiteral(matcher.group(3));
        if (operator == null || field == null || value == UnsupportedLiteral.INSTANCE) {
            return null;
        }
        return Predicate.of(field, operator, Arrays.asList(value),
                field + " " + operator + " " + sqlLiteral(value));
    }

    private static String serializableOperator(String function) {
        String compact = function == null ? "" : function.trim().replaceAll("[^A-Za-z0-9<>=]+", "")
                .toUpperCase(Locale.ENGLISH);
        if ("EQUALS".equals(compact) || "=".equals(compact)) {
            return "=";
        }
        if ("GREATERTHAN".equals(compact) || ">".equals(compact)) {
            return ">";
        }
        if ("GREATERTHANOREQUAL".equals(compact) || ">=".equals(compact)) {
            return ">=";
        }
        if ("LESSTHAN".equals(compact) || "<".equals(compact)) {
            return "<";
        }
        if ("LESSTHANOREQUAL".equals(compact) || "<=".equals(compact)) {
            return "<=";
        }
        return null;
    }

    private static String serializableField(String value) {
        String field = stripIdentifierQuote(value);
        if (field.isEmpty() || field.contains("(") || field.contains(",")) {
            return null;
        }
        return field;
    }

    private static Object serializableLiteral(String value) {
        String text = value == null ? "" : value.trim();
        if ("NULL".equalsIgnoreCase(text)) {
            return null;
        }
        if (text.startsWith("'") && text.endsWith("'") && text.length() >= 2) {
            return text.substring(1, text.length() - 1).replace("''", "'");
        }
        if ("TRUE".equalsIgnoreCase(text) || "FALSE".equalsIgnoreCase(text)) {
            return Boolean.valueOf(text);
        }
        try {
            if (text.contains(".")) {
                return new BigDecimal(text);
            }
            return Long.valueOf(text);
        } catch (NumberFormatException ignored) {
            return UnsupportedLiteral.INSTANCE;
        }
    }

    private static String fieldName(ResolvedExpression expression) {
        if (expression instanceof FieldReferenceExpression) {
            return ((FieldReferenceExpression) expression).getName();
        }
        if (expression instanceof NestedFieldReferenceExpression) {
            return nestedFieldReferenceName((NestedFieldReferenceExpression) expression);
        }
        String nestedField = nestedFieldName(expression);
        if (nestedField != null) {
            return nestedField;
        }
        return null;
    }

    private static String nestedFieldReferenceName(NestedFieldReferenceExpression expression) {
        List<String> parts = new ArrayList<String>();
        for (String fieldName : expression.getFieldNames()) {
            String part = stripIdentifierQuote(fieldName);
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts.isEmpty() ? null : String.join(".", parts);
    }

    private static String nestedFieldName(ResolvedExpression expression) {
        if (!(expression instanceof CallExpression)) {
            return null;
        }
        CallExpression call = (CallExpression) expression;
        List<ResolvedExpression> children = call.getResolvedChildren();
        if (children.size() != 2 || !looksLikeNestedFieldAccess(call)) {
            return null;
        }
        String parent = fieldName(children.get(0));
        Object child = literal(children.get(1));
        if (parent == null || child == UnsupportedLiteral.INSTANCE || child == null) {
            return null;
        }
        String childName = stripIdentifierQuote(String.valueOf(child));
        return childName.isEmpty() ? null : parent + "." + childName;
    }

    private static boolean looksLikeNestedFieldAccess(CallExpression call) {
        String functionText = String.valueOf(call.getFunctionDefinition()).toUpperCase(Locale.ENGLISH);
        if (functionText.contains("GET") || functionText.contains("FIELD")) {
            return true;
        }
        String expression = serializable(call).toUpperCase(Locale.ENGLISH);
        return expression.contains("[") || expression.contains(".") || expression.contains("GET(");
    }

    private static String stripIdentifierQuote(String value) {
        String text = value == null ? "" : value.trim();
        while ((text.startsWith("`") && text.endsWith("`"))
                || (text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("'") && text.endsWith("'"))) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    private static Object literal(ResolvedExpression expression) {
        if (!(expression instanceof ValueLiteralExpression)) {
            return UnsupportedLiteral.INSTANCE;
        }
        ValueLiteralExpression literal = (ValueLiteralExpression) expression;
        if (literal.isNull()) {
            return null;
        }
        List<Class<?>> types = Arrays.<Class<?>>asList(
                LocalDate.class,
                LocalDateTime.class,
                LocalTime.class,
                java.sql.Date.class,
                Timestamp.class,
                Time.class,
                BigDecimal.class,
                Integer.class,
                Long.class,
                Double.class,
                Float.class,
                Boolean.class,
                String.class);
        for (Class<?> type : types) {
            Optional<?> value = literal.getValueAs(type);
            if (value.isPresent()) {
                return value.get();
            }
        }
        Optional<?> objectValue = literal.getValueAs(Object.class);
        return objectValue.<Object>map(value -> value).orElse(UnsupportedLiteral.INSTANCE);
    }

    private static String sqlLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof LocalDate) {
            return "'" + value + "'";
        }
        if (value instanceof java.sql.Date) {
            return "'" + ((java.sql.Date) value).toLocalDate() + "'";
        }
        if (value instanceof LocalDateTime) {
            return "'" + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format((LocalDateTime) value) + "'";
        }
        if (value instanceof Timestamp) {
            return "'" + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .format(((Timestamp) value).toLocalDateTime()) + "'";
        }
        if (value instanceof LocalTime || value instanceof Time) {
            return "'" + value + "'";
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    private static String auditValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate) {
            return String.valueOf(value);
        }
        if (value instanceof java.sql.Date) {
            return String.valueOf(((java.sql.Date) value).toLocalDate());
        }
        if (value instanceof LocalDateTime) {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format((LocalDateTime) value);
        }
        if (value instanceof Timestamp) {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(((Timestamp) value).toLocalDateTime());
        }
        return String.valueOf(value);
    }

    private static String flip(String operator) {
        if (">".equals(operator)) {
            return "<";
        }
        if (">=".equals(operator)) {
            return "<=";
        }
        if ("<".equals(operator)) {
            return ">";
        }
        if ("<=".equals(operator)) {
            return ">=";
        }
        return operator;
    }

    private static String serializable(ResolvedExpression expression) {
        try {
            return expression.asSerializableString();
        } catch (Exception ignored) {
            return String.valueOf(expression);
        }
    }

    static final class Translation {
        private final List<ResolvedExpression> acceptedFilters;
        private final List<ResolvedExpression> remainingFilters;
        private final List<String> pushedFilterSql;
        private final List<String> remainingFilterSql;
        private final List<FilePathPushdownFilter> pathContextFilters;
        private final List<Map<String, Object>> httpPushdownFilters;
        private final boolean httpFilterAlwaysFalse;

        Translation(List<ResolvedExpression> acceptedFilters,
                    List<ResolvedExpression> remainingFilters,
                    List<String> pushedFilterSql,
                    List<String> remainingFilterSql,
                    List<FilePathPushdownFilter> pathContextFilters,
                    List<Map<String, Object>> httpPushdownFilters,
                    boolean httpFilterAlwaysFalse) {
            this.acceptedFilters = acceptedFilters;
            this.remainingFilters = remainingFilters;
            this.pushedFilterSql = pushedFilterSql;
            this.remainingFilterSql = remainingFilterSql;
            this.pathContextFilters = pathContextFilters;
            this.httpPushdownFilters = httpPushdownFilters;
            this.httpFilterAlwaysFalse = httpFilterAlwaysFalse;
        }

        List<ResolvedExpression> getAcceptedFilters() {
            return acceptedFilters;
        }

        List<ResolvedExpression> getRemainingFilters() {
            return remainingFilters;
        }

        List<String> getPushedFilterSql() {
            return pushedFilterSql;
        }

        List<String> getRemainingFilterSql() {
            return remainingFilterSql;
        }

        List<FilePathPushdownFilter> getPathContextFilters() {
            return pathContextFilters;
        }

        List<Map<String, Object>> getHttpPushdownFilters() {
            return httpPushdownFilters;
        }

        boolean isHttpFilterAlwaysFalse() {
            return httpFilterAlwaysFalse;
        }
    }

    private static final class Predicate {
        private final List<SimplePredicate> simplePredicates;
        private final String sql;

        private Predicate(List<SimplePredicate> simplePredicates, String sql) {
            this.simplePredicates = simplePredicates;
            this.sql = sql;
        }

        static Predicate of(String field, String operator, List<Object> values, String sql) {
            List<SimplePredicate> predicates = new ArrayList<SimplePredicate>();
            predicates.add(new SimplePredicate(field, operator, values));
            return new Predicate(predicates, sql);
        }

        String getSql() {
            return sql;
        }

        boolean hasNoPathFields(FilePathPushdownConfig pathConfig) {
            for (SimplePredicate predicate : simplePredicates) {
                if (pathConfig.isPathContextField(predicate.field)) {
                    return false;
                }
            }
            return true;
        }

        boolean hasOnlyPathFields(FilePathPushdownConfig pathConfig) {
            if (!pathConfig.isEnabled()) {
                return false;
            }
            for (SimplePredicate predicate : simplePredicates) {
                if (!pathConfig.isPathContextField(predicate.field)) {
                    return false;
                }
            }
            return true;
        }

        boolean supportsPathDate() {
            Set<String> allowed = new LinkedHashSet<String>(Arrays.asList("=", "<", "<=", ">", ">=", "BETWEEN", "IN"));
            for (SimplePredicate predicate : simplePredicates) {
                if (!allowed.contains(predicate.operator.toUpperCase(Locale.ENGLISH)) || predicate.values.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        List<FilePathPushdownFilter> toPathFilters(FilePathPushdownConfig pathConfig, ResolvedExpression expression) {
            List<FilePathPushdownFilter> filters = new ArrayList<FilePathPushdownFilter>();
            for (SimplePredicate predicate : simplePredicates) {
                FilePathPushdownConfig.Context context = pathConfig.findContext(predicate.field);
                if (context == null) {
                    continue;
                }
                List<String> values = new ArrayList<String>();
                for (Object value : predicate.values) {
                    values.add(auditValue(value));
                }
                filters.add(new FilePathPushdownFilter(predicate.field, context.getDisplayName(),
                        predicate.operator, values, serializable(expression)));
            }
            return filters;
        }

        HttpPushdownDecision toHttpPushdown(HttpPushdownMappingConfig httpConfig, ResolvedExpression expression) {
            if (httpConfig == null) {
                return HttpPushdownDecision.notAccepted();
            }
            List<Map<String, Object>> filters = new ArrayList<Map<String, Object>>();
            for (SimplePredicate predicate : simplePredicates) {
                HttpFieldRef fieldRef = HttpFieldRef.parse(predicate.field);
                if ("=".equals(HttpPushdownMappingConfig.normalizeOperator(predicate.operator))
                        && predicate.values.size() == 1
                        && predicate.values.get(0) == null) {
                    return HttpPushdownDecision.alwaysFalse();
                }
                HttpPushdownMappingConfig.Mapping mapping = resolveHttpMapping(httpConfig, fieldRef, predicate);
                if (mapping == null) {
                    return HttpPushdownDecision.notAccepted();
                }
                filters.add(mapping.toPushdownPredicate(predicate.operator, predicate.values, serializable(expression)));
            }
            return HttpPushdownDecision.accepted(filters);
        }

        private HttpPushdownMappingConfig.Mapping resolveHttpMapping(HttpPushdownMappingConfig httpConfig,
                                                                     HttpFieldRef fieldRef,
                                                                     SimplePredicate predicate) {
            HttpPushdownMappingConfig.Mapping mapping;
            if (fieldRef.explicitLocation != null) {
                if ("header".equals(fieldRef.explicitLocation)
                        && HttpPushdownMappingConfig.isReservedHttpHeader(fieldRef.field)) {
                    throw new IllegalArgumentException("HTTP header " + fieldRef.field
                            + " 是协议/传输保留头，不能通过 SQL 条件下推");
                }
                mapping = httpConfig.findByLocationAndField(fieldRef.explicitLocation, fieldRef.field);
                if (mapping == null) {
                    throw new IllegalArgumentException("HTTP 下推字段 " + fieldRef.explicitLocation + "." + fieldRef.field
                            + " 不符合内置参数下推规则");
                }
                assertHttpOperatorSupported(mapping, predicate.operator, fieldRef.field);
                return mapping;
            }
            List<HttpPushdownMappingConfig.Mapping> mappings = httpConfig.findByField(fieldRef.field);
            if (mappings.isEmpty()) {
                return null;
            }
            List<HttpPushdownMappingConfig.Mapping> supportedMappings = new ArrayList<HttpPushdownMappingConfig.Mapping>();
            for (HttpPushdownMappingConfig.Mapping item : mappings) {
                if (item.supportsOperator(predicate.operator)) {
                    supportedMappings.add(item);
                }
            }
            if (supportedMappings.isEmpty()) {
                return null;
            }
            mappings = supportedMappings;
            if (mappings.size() > 1) {
                List<String> locations = new ArrayList<String>();
                List<String> targets = new ArrayList<String>();
                for (HttpPushdownMappingConfig.Mapping item : mappings) {
                    locations.add(item.getLocation());
                    targets.add(item.targetDescription());
                }
                if (new LinkedHashSet<String>(locations).size() == 1) {
                    throw new IllegalArgumentException("HTTP 下推字段 " + fieldRef.field + " 同时映射到 "
                            + String.join(", ", targets)
                            + "，请改用 a." + locations.get(0) + ".<完整路径> 明确指定请求目标");
                }
                throw new IllegalArgumentException("HTTP 下推字段 " + fieldRef.field + " 同时映射到 "
                        + String.join(", ", locations) + "，请改用 a.<location>." + fieldRef.field + " 明确指定参数位置");
            }
            mapping = mappings.get(0);
            return mapping;
        }

        private void assertHttpOperatorSupported(HttpPushdownMappingConfig.Mapping mapping,
                                                 String operator,
                                                 String field) {
            if (!mapping.supportsOperator(operator)) {
                throw new IllegalArgumentException("HTTP 下推字段 " + field + " 不支持操作符 "
                        + operator + "，模型当前支持: "
                        + String.join(", ", mapping.getSupportedOperators()));
            }
        }
    }

    private static final class HttpPushdownDecision {
        private final boolean accepted;
        private final boolean alwaysFalse;
        private final List<Map<String, Object>> pushdownFilters;

        private HttpPushdownDecision(boolean accepted,
                                     boolean alwaysFalse,
                                     List<Map<String, Object>> pushdownFilters) {
            this.accepted = accepted;
            this.alwaysFalse = alwaysFalse;
            this.pushdownFilters = pushdownFilters == null
                    ? Collections.<Map<String, Object>>emptyList()
                    : pushdownFilters;
        }

        static HttpPushdownDecision accepted(List<Map<String, Object>> pushdownFilters) {
            return new HttpPushdownDecision(true, false, pushdownFilters);
        }

        static HttpPushdownDecision alwaysFalse() {
            return new HttpPushdownDecision(true, true, Collections.<Map<String, Object>>emptyList());
        }

        static HttpPushdownDecision notAccepted() {
            return new HttpPushdownDecision(false, false, Collections.<Map<String, Object>>emptyList());
        }

        boolean isAccepted() {
            return accepted;
        }

        boolean isAlwaysFalse() {
            return alwaysFalse;
        }

        List<Map<String, Object>> getPushdownFilters() {
            return pushdownFilters;
        }
    }

    private static final class HttpFieldRef {
        private final String explicitLocation;
        private final String field;

        private HttpFieldRef(String explicitLocation, String field) {
            this.explicitLocation = explicitLocation;
            this.field = field;
        }

        static HttpFieldRef parse(String rawField) {
            String field = normalizeHttpField(rawField);
            String[] parts = field.split("\\.");
            if (parts.length >= 2 && HttpPushdownMappingConfig.isHttpLocation(parts[0])) {
                List<String> pathParts = new ArrayList<String>();
                for (int index = 1; index < parts.length; index++) {
                    String part = stripIdentifierQuote(parts[index]);
                    if (!part.isEmpty()) {
                        pathParts.add(part);
                    }
                }
                return new HttpFieldRef(HttpPushdownMappingConfig.normalizeLocation(parts[0]),
                        String.join(".", pathParts));
            }
            return new HttpFieldRef(null, field);
        }

        private static String normalizeHttpField(String rawField) {
            String field = stripIdentifierQuote(rawField == null ? "" : rawField.trim());
            String[] parts = field.split("\\.");
            if (parts.length <= 1) {
                return stripIdentifierQuote(field);
            }
            List<String> normalizedParts = new ArrayList<String>();
            for (String part : parts) {
                String text = stripIdentifierQuote(part);
                if (!text.isEmpty()) {
                    normalizedParts.add(text);
                }
            }
            return String.join(".", normalizedParts);
        }
    }

    private static final class SimplePredicate {
        private final String field;
        private final String operator;
        private final List<Object> values;

        private SimplePredicate(String field, String operator, List<Object> values) {
            this.field = field;
            this.operator = operator;
            this.values = values == null ? new ArrayList<Object>() : new ArrayList<Object>(values);
        }
    }

    private enum UnsupportedLiteral {
        INSTANCE
    }
}
