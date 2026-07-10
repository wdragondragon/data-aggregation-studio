package com.jdragon.studio.flink.connector;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class FilePathPushdownResolver {
    private FilePathPushdownResolver() {
    }

    static List<ResolvedFilePath> resolve(AggregationFlinkTableRuntime runtime) {
        FilePathPushdownConfig config = FilePathPushdownConfig.from(runtime.getModelMetadata());
        if (!config.isEnabled() || config.getContexts().isEmpty()) {
            return defaultPath(runtime);
        }
        List<ResolvedFilePath> result = new ArrayList<ResolvedFilePath>();
        Map<String, List<FilePathPushdownFilter>> filtersByField = filtersByField(runtime.getPathContextFilters());
        for (FilePathPushdownConfig.Context context : config.getContexts()) {
            List<FilePathPushdownFilter> filters = filtersByField.get(context.getField());
            if ((filters == null || filters.isEmpty()) && config.isRequired()) {
                throw new IllegalArgumentException("File path context '" + context.getField()
                        + "' requires a bounded path-time filter to avoid full path scan");
            }
            List<LocalDate> dates = resolveDates(context, filters);
            List<String> pathExpressions = context.getPathExpressions();
            if (pathExpressions.isEmpty()) {
                pathExpressions.add(resolveDefaultPathExpression(runtime));
            }
            for (LocalDate date : dates) {
                Map<String, LocalDate> contextValues = new LinkedHashMap<String, LocalDate>();
                contextValues.put(context.getField(), date);
                for (String expression : pathExpressions) {
                    String path = resolveWithRoot(runtime, expression);
                    String resolved = AggregationDynamicFunctionEvaluator.replaceAll(path, date.atStartOfDay());
                    result.add(new ResolvedFilePath(resolved, contextValues));
                }
            }
        }
        if (result.isEmpty()) {
            return defaultPath(runtime);
        }
        return deduplicate(result);
    }

    private static List<ResolvedFilePath> defaultPath(AggregationFlinkTableRuntime runtime) {
        List<ResolvedFilePath> result = new ArrayList<ResolvedFilePath>();
        String path = resolveWithRoot(runtime, resolveDefaultPathExpression(runtime));
        result.add(new ResolvedFilePath(AggregationDynamicFunctionEvaluator.replaceAll(path), new LinkedHashMap<String, LocalDate>()));
        return result;
    }

    private static List<ResolvedFilePath> deduplicate(List<ResolvedFilePath> paths) {
        Set<String> seen = new LinkedHashSet<String>();
        List<ResolvedFilePath> result = new ArrayList<ResolvedFilePath>();
        for (ResolvedFilePath path : paths) {
            if (seen.add(path.getPath())) {
                result.add(path);
            }
        }
        return result;
    }

    private static Map<String, List<FilePathPushdownFilter>> filtersByField(List<FilePathPushdownFilter> filters) {
        Map<String, List<FilePathPushdownFilter>> result = new LinkedHashMap<String, List<FilePathPushdownFilter>>();
        if (filters == null) {
            return result;
        }
        for (FilePathPushdownFilter filter : filters) {
            if (filter == null || filter.getField() == null) {
                continue;
            }
            result.computeIfAbsent(filter.getField(), key -> new ArrayList<FilePathPushdownFilter>()).add(filter);
        }
        return result;
    }

    private static List<LocalDate> resolveDates(FilePathPushdownConfig.Context context, List<FilePathPushdownFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            List<LocalDate> dates = new ArrayList<LocalDate>();
            dates.add(LocalDate.now());
            return dates;
        }
        DateWindow window = new DateWindow();
        for (FilePathPushdownFilter filter : filters) {
            String operator = filter.getOperator() == null ? "" : filter.getOperator().toUpperCase(Locale.ENGLISH);
            if ("=".equals(operator)) {
                window.exact(parseDate(filter.getValues().get(0)));
            } else if ("IN".equals(operator)) {
                for (String value : filter.getValues()) {
                    window.exact(parseDate(value));
                }
            } else if ("BETWEEN".equals(operator)) {
                window.lower(parseDate(filter.getValues().get(0)));
                window.upper(parseDate(filter.getValues().get(1)));
            } else if (">=".equals(operator)) {
                window.lower(parseDate(filter.getValues().get(0)));
            } else if (">".equals(operator)) {
                window.lower(parseDate(filter.getValues().get(0)).plusDays(1));
            } else if ("<=".equals(operator)) {
                window.upper(parseDate(filter.getValues().get(0)));
            } else if ("<".equals(operator)) {
                window.upper(parseDate(filter.getValues().get(0)).minusDays(1));
            }
        }
        List<LocalDate> dates = window.expand(context.getMaxExpandedDates());
        if (dates.isEmpty()) {
            throw new IllegalArgumentException("File path context '" + context.getField()
                    + "' did not receive a bounded date filter");
        }
        return dates;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Path context date value is empty");
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 10) {
            return LocalDate.parse(trimmed.substring(0, 10));
        }
        return LocalDate.parse(trimmed);
    }

    private static String resolveDefaultPathExpression(AggregationFlinkTableRuntime runtime) {
        Map<String, Object> metadata = runtime.getModelMetadata();
        Object path = metadata.get("path");
        if (path == null) {
            Object rootPath = metadata.get("rootPath");
            Object partition = firstText(metadata.get("partition"), metadata.get("pattern"));
            if (hasText(rootPath) && hasText(partition)) {
                return joinPath(String.valueOf(rootPath), String.valueOf(partition));
            }
            Object fileName = firstText(metadata.get("fileName"), metadata.get("physicalName"));
            if (hasText(rootPath) && hasText(fileName)) {
                return joinPath(String.valueOf(rootPath), String.valueOf(fileName));
            }
            path = fileName;
        }
        if (path == null) {
            path = runtime.getPhysicalLocator();
        }
        if (path == null) {
            path = runtime.getTableName();
        }
        return String.valueOf(path);
    }

    private static String resolveWithRoot(AggregationFlinkTableRuntime runtime, String expression) {
        String value = expression == null ? "" : expression;
        Object root = runtime.getModelMetadata().get("rootPath");
        if (root != null && !value.startsWith("/") && !value.contains(":")) {
            String rootPath = String.valueOf(root);
            return rootPath.endsWith("/") ? rootPath + value : rootPath + "/" + value;
        }
        return value;
    }

    private static boolean hasText(Object value) {
        return value != null && !String.valueOf(value).trim().isEmpty();
    }

    private static Object firstText(Object first, Object second) {
        return hasText(first) ? first : second;
    }

    private static String joinPath(String root, String name) {
        String normalizedRoot = root == null ? "" : root.trim().replace('\\', '/');
        String normalizedName = name == null ? "" : name.trim().replace('\\', '/');
        if (normalizedName.startsWith("/") || normalizedName.contains("://")) {
            return normalizedName;
        }
        while (normalizedRoot.endsWith("/") && normalizedRoot.length() > 1) {
            normalizedRoot = normalizedRoot.substring(0, normalizedRoot.length() - 1);
        }
        while (normalizedName.startsWith("/")) {
            normalizedName = normalizedName.substring(1);
        }
        if (normalizedRoot.isEmpty() || "/".equals(normalizedRoot)) {
            return "/" + normalizedName;
        }
        return normalizedRoot + "/" + normalizedName;
    }

    private static final class DateWindow {
        private final Set<LocalDate> exactDates = new LinkedHashSet<LocalDate>();
        private LocalDate lower;
        private LocalDate upper;

        void exact(LocalDate date) {
            if (date != null) {
                exactDates.add(date);
            }
        }

        void lower(LocalDate date) {
            if (date != null && (lower == null || date.isAfter(lower))) {
                lower = date;
            }
        }

        void upper(LocalDate date) {
            if (date != null && (upper == null || date.isBefore(upper))) {
                upper = date;
            }
        }

        List<LocalDate> expand(int maxExpandedDates) {
            int max = maxExpandedDates <= 0 ? 31 : maxExpandedDates;
            List<LocalDate> result = new ArrayList<LocalDate>();
            if (!exactDates.isEmpty()) {
                for (LocalDate date : exactDates) {
                    if ((lower == null || !date.isBefore(lower)) && (upper == null || !date.isAfter(upper))) {
                        result.add(date);
                    }
                }
            } else {
                if (lower == null || upper == null) {
                    return result;
                }
                LocalDate current = lower;
                while (!current.isAfter(upper)) {
                    result.add(current);
                    if (result.size() > max) {
                        throw new IllegalArgumentException("File path date range exceeds maxExpandedDates " + max);
                    }
                    current = current.plusDays(1);
                }
            }
            if (result.size() > max) {
                throw new IllegalArgumentException("File path date list exceeds maxExpandedDates " + max);
            }
            return result;
        }
    }
}
