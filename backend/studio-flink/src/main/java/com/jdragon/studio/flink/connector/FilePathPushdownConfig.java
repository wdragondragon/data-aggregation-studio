package com.jdragon.studio.flink.connector;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.types.DataType;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class FilePathPushdownConfig implements Serializable {
    private static final FilePathPushdownConfig DISABLED =
            new FilePathPushdownConfig(false, false, Collections.<Context>emptyList());

    private final boolean enabled;
    private final boolean required;
    private final List<Context> contexts;

    private FilePathPushdownConfig(boolean enabled, boolean required, List<Context> contexts) {
        this.enabled = enabled;
        this.required = required;
        this.contexts = contexts == null ? new ArrayList<Context>() : new ArrayList<Context>(contexts);
    }

    public static FilePathPushdownConfig from(Map<String, Object> modelMetadata) {
        Object configured = modelMetadata == null ? null : modelMetadata.get("filePathPushdown");
        if (!(configured instanceof Map<?, ?>)) {
            return DISABLED;
        }
        Map<?, ?> raw = (Map<?, ?>) configured;
        boolean enabled = asBoolean(raw.get("enabled"), true);
        if (!enabled) {
            return DISABLED;
        }
        boolean required = asBoolean(raw.get("required"), false);
        List<Context> contexts = new ArrayList<Context>();
        Object rawContexts = raw.get("contexts");
        if (rawContexts instanceof List<?>) {
            for (Object item : (List<?>) rawContexts) {
                if (item instanceof Map<?, ?>) {
                    Context context = Context.from((Map<?, ?>) item);
                    if (context != null) {
                        contexts.add(context);
                    }
                }
            }
        }
        return new FilePathPushdownConfig(true, required, contexts);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isRequired() {
        return required;
    }

    public List<Context> getContexts() {
        return new ArrayList<Context>(contexts);
    }

    public Context findContext(String field) {
        if (field == null) {
            return null;
        }
        for (Context context : contexts) {
            if (field.equals(context.getField())) {
                return context;
            }
        }
        return null;
    }

    public boolean isPathContextField(String field) {
        return findContext(field) != null;
    }

    public List<Map<String, Object>> asPromptContexts() {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (Context context : contexts) {
            rows.add(context.asMap());
        }
        return rows;
    }

    private static boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public static final class Context implements Serializable {
        private final String field;
        private final String displayName;
        private final List<String> aliases;
        private final String type;
        private final List<String> pathExpressions;
        private final int maxExpandedDates;

        private Context(String field,
                        String displayName,
                        List<String> aliases,
                        String type,
                        List<String> pathExpressions,
                        int maxExpandedDates) {
            this.field = field;
            this.displayName = displayName;
            this.aliases = aliases == null ? new ArrayList<String>() : new ArrayList<String>(aliases);
            this.type = type == null ? "DATE" : type;
            this.pathExpressions = pathExpressions == null
                    ? new ArrayList<String>()
                    : new ArrayList<String>(pathExpressions);
            this.maxExpandedDates = maxExpandedDates <= 0 ? 31 : maxExpandedDates;
        }

        static Context from(Map<?, ?> map) {
            String field = firstText(map.get("field"), map.get("name"));
            if (field == null || !field.startsWith("__path_")) {
                return null;
            }
            String displayName = firstText(map.get("displayName"), field);
            String type = firstText(map.get("type"), "DATE");
            List<String> aliases = toStringList(map.get("aliases"));
            List<String> expressions = toStringList(map.get("pathExpressions"));
            String expression = firstText(map.get("pathExpression"), null);
            if (expression != null) {
                expressions.add(expression);
            }
            return new Context(field, displayName, aliases, type, expressions, asInteger(map.get("maxExpandedDates"), 31));
        }

        public String getField() {
            return field;
        }

        public String getDisplayName() {
            return displayName;
        }

        public List<String> getAliases() {
            return new ArrayList<String>(aliases);
        }

        public String getType() {
            return type;
        }

        public List<String> getPathExpressions() {
            return new ArrayList<String>(pathExpressions);
        }

        public int getMaxExpandedDates() {
            return maxExpandedDates;
        }

        public DataType toDataType() {
            String normalized = type == null ? "" : type.trim().toUpperCase(Locale.ENGLISH);
            if ("TIMESTAMP".equals(normalized) || "DATETIME".equals(normalized)) {
                return DataTypes.TIMESTAMP(3);
            }
            if ("STRING".equals(normalized) || "VARCHAR".equals(normalized)) {
                return DataTypes.STRING();
            }
            return DataTypes.DATE();
        }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("field", field);
            map.put("displayName", displayName);
            map.put("aliases", new ArrayList<String>(aliases));
            map.put("type", type);
            map.put("pathExpressions", new ArrayList<String>(pathExpressions));
            map.put("maxExpandedDates", maxExpandedDates);
            return map;
        }
    }

    private static List<String> toStringList(Object value) {
        List<String> result = new ArrayList<String>();
        if (value instanceof List<?>) {
            for (Object item : (List<?>) value) {
                if (item != null && !String.valueOf(item).trim().isEmpty()) {
                    result.add(String.valueOf(item).trim());
                }
            }
        } else if (value != null && !String.valueOf(value).trim().isEmpty()) {
            result.add(String.valueOf(value).trim());
        }
        return result;
    }

    private static String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).trim().isEmpty()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private static int asInteger(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
