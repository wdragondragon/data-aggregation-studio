package com.jdragon.studio.infra.service;

import com.jdragon.studio.infra.entity.DataModelEntity;
import com.jdragon.studio.infra.entity.DatasourceEntity;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class DataModelLineageTextSupport {

    private DataModelLineageTextSupport() {
    }

    static Set<String> extractModelFields(DataModelEntity model) {
        Set<String> fields = new LinkedHashSet<String>();
        if (model == null || model.getTechnicalMetadata() == null) {
            return fields;
        }
        Object rawColumns = model.getTechnicalMetadata().get("columns");
        if (!(rawColumns instanceof List)) {
            return fields;
        }
        for (Object candidate : (List<?>) rawColumns) {
            if (!(candidate instanceof Map)) {
                continue;
            }
            Object name = ((Map<?, ?>) candidate).get("name");
            if (name != null && !String.valueOf(name).trim().isEmpty()) {
                fields.add(String.valueOf(name));
            }
        }
        return fields;
    }

    static boolean containsIgnoreCase(Collection<String> fields, String expected) {
        if (fields == null || expected == null) {
            return false;
        }
        for (String field : fields) {
            if (field != null && field.equalsIgnoreCase(expected)) {
                return true;
            }
        }
        return false;
    }

    static String findOriginalField(Collection<String> fields, String expected) {
        if (fields == null) {
            return expected;
        }
        for (String field : fields) {
            if (field != null && field.equalsIgnoreCase(expected)) {
                return field;
            }
        }
        return expected;
    }

    static String resolveDatabaseName(DatasourceEntity datasource) {
        return firstNonBlank(resolveStringMetadata(datasource, "database", "schema", "catalog"),
                resolveStringMetadata(datasource, "dbName"));
    }

    static String resolveStringMetadata(DatasourceEntity datasource, String... keys) {
        if (datasource == null || datasource.getTechnicalMetadata() == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = datasource.getTechnicalMetadata().get(key);
            if (value != null && !String.valueOf(value).trim().isEmpty()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    static String safeText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    static String normalizeText(String value) {
        return blankToNull(value == null ? null : value.trim().toLowerCase(Locale.ENGLISH));
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
