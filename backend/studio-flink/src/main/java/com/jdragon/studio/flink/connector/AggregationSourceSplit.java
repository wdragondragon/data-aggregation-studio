package com.jdragon.studio.flink.connector;

import org.apache.flink.api.connector.source.SourceSplit;

public class AggregationSourceSplit implements SourceSplit {
    private final String splitId;

    public AggregationSourceSplit(String splitId) {
        this.splitId = splitId;
    }

    @Override
    public String splitId() {
        return splitId;
    }
}
