package com.jdragon.studio.flink.connector;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

class ResolvedFilePath {
    private final String path;
    private final Map<String, LocalDate> contextValues;

    ResolvedFilePath(String path, Map<String, LocalDate> contextValues) {
        this.path = path;
        this.contextValues = contextValues == null
                ? new LinkedHashMap<String, LocalDate>()
                : new LinkedHashMap<String, LocalDate>(contextValues);
    }

    String getPath() {
        return path;
    }

    Map<String, LocalDate> getContextValues() {
        return new LinkedHashMap<String, LocalDate>(contextValues);
    }
}
