package com.jdragon.studio.flink.execution;

import java.util.ArrayList;
import java.util.List;

public class FlinkExecutionRequest {
    private final String sql;
    private final List<String> createTableDdls;
    private final boolean streamingMode;
    private final int maxRows;

    public FlinkExecutionRequest(String sql, List<String> createTableDdls, boolean streamingMode, int maxRows) {
        this.sql = sql;
        this.createTableDdls = createTableDdls == null ? new ArrayList<String>() : new ArrayList<String>(createTableDdls);
        this.streamingMode = streamingMode;
        this.maxRows = maxRows;
    }

    public String getSql() {
        return sql;
    }

    public List<String> getCreateTableDdls() {
        return createTableDdls;
    }

    public boolean isStreamingMode() {
        return streamingMode;
    }

    public int getMaxRows() {
        return maxRows;
    }
}
