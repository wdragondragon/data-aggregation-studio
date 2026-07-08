package com.jdragon.studio.flink.connector;

import org.apache.flink.table.expressions.CallExpression;
import org.apache.flink.table.expressions.FieldReferenceExpression;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.expressions.ValueLiteralExpression;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

final class AggregationFilterPushDownTranslator {
    private AggregationFilterPushDownTranslator() {
    }

    static Translation translate(List<ResolvedExpression> filters,
                                 AggregationFlinkTableRuntime runtime,
                                 AggregationPluginKind pluginKind) {
        FilePathPushdownConfig pathConfig = FilePathPushdownConfig.from(runtime.getModelMetadata());
        List<ResolvedExpression> accepted = new ArrayList<ResolvedExpression>();
        List<ResolvedExpression> remaining = new ArrayList<ResolvedExpression>();
        List<String> pushedSql = new ArrayList<String>();
        List<String> remainingSql = new ArrayList<String>();
        List<FilePathPushdownFilter> pathFilters = new ArrayList<FilePathPushdownFilter>();
        if (filters == null) {
            return new Translation(accepted, remaining, pushedSql, remainingSql, pathFilters);
        }
        for (ResolvedExpression filter : filters) {
            for (ResolvedExpression conjunct : flattenConjuncts(filter)) {
                Predicate predicate = toPredicate(conjunct);
                if (predicate == null) {
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
                remaining.add(conjunct);
                remainingSql.add(serializable(conjunct));
            }
        }
        return new Translation(accepted, remaining, pushedSql, remainingSql, pathFilters);
    }

    private static List<ResolvedExpression> flattenConjuncts(ResolvedExpression expression) {
        List<ResolvedExpression> result = new ArrayList<ResolvedExpression>();
        collectConjuncts(expression, result);
        return result;
    }

    private static void collectConjuncts(ResolvedExpression expression, List<ResolvedExpression> result) {
        if (expression instanceof CallExpression) {
            CallExpression call = (CallExpression) expression;
            if (call.getFunctionDefinition() == BuiltInFunctionDefinitions.AND
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
        if (function == BuiltInFunctionDefinitions.EQUALS) {
            return binary(children, "=");
        }
        if (function == BuiltInFunctionDefinitions.GREATER_THAN) {
            return binary(children, ">");
        }
        if (function == BuiltInFunctionDefinitions.GREATER_THAN_OR_EQUAL) {
            return binary(children, ">=");
        }
        if (function == BuiltInFunctionDefinitions.LESS_THAN) {
            return binary(children, "<");
        }
        if (function == BuiltInFunctionDefinitions.LESS_THAN_OR_EQUAL) {
            return binary(children, "<=");
        }
        if (function == BuiltInFunctionDefinitions.IS_NULL && children.size() == 1) {
            String field = fieldName(children.get(0));
            return field == null ? null : Predicate.of(field, "IS NULL", new ArrayList<Object>(), field + " IS NULL");
        }
        if (function == BuiltInFunctionDefinitions.IS_NOT_NULL && children.size() == 1) {
            String field = fieldName(children.get(0));
            return field == null ? null : Predicate.of(field, "IS NOT NULL", new ArrayList<Object>(), field + " IS NOT NULL");
        }
        if (function == BuiltInFunctionDefinitions.BETWEEN && children.size() == 3) {
            String field = fieldName(children.get(0));
            Object lower = literal(children.get(1));
            Object upper = literal(children.get(2));
            if (field == null || lower == UnsupportedLiteral.INSTANCE || upper == UnsupportedLiteral.INSTANCE) {
                return null;
            }
            return Predicate.of(field, "BETWEEN", Arrays.asList(lower, upper),
                    field + " BETWEEN " + sqlLiteral(lower) + " AND " + sqlLiteral(upper));
        }
        if (function == BuiltInFunctionDefinitions.IN && children.size() >= 2) {
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
        return null;
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

    private static String fieldName(ResolvedExpression expression) {
        if (expression instanceof FieldReferenceExpression) {
            return ((FieldReferenceExpression) expression).getName();
        }
        return null;
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

        Translation(List<ResolvedExpression> acceptedFilters,
                    List<ResolvedExpression> remainingFilters,
                    List<String> pushedFilterSql,
                    List<String> remainingFilterSql,
                    List<FilePathPushdownFilter> pathContextFilters) {
            this.acceptedFilters = acceptedFilters;
            this.remainingFilters = remainingFilters;
            this.pushedFilterSql = pushedFilterSql;
            this.remainingFilterSql = remainingFilterSql;
            this.pathContextFilters = pathContextFilters;
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
