package com.jdragon.studio.flink.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FlinkExecutionResult {
    private List<String> columns = new ArrayList<String>();
    private List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns == null ? new ArrayList<String>() : new ArrayList<String>(columns);
    }

    public List<Map<String, Object>> getRows() {
        return rows;
    }

    public void setRows(List<Map<String, Object>> rows) {
        this.rows = rows == null ? new ArrayList<Map<String, Object>>() : new ArrayList<Map<String, Object>>(rows);
    }

    public void addRow(Map<String, Object> row) {
        this.rows.add(row == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(row));
    }
}
