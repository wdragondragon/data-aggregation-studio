package com.jdragon.studio.flink.connector;

import org.apache.flink.api.connector.source.SplitEnumerator;
import org.apache.flink.api.connector.source.SplitEnumeratorContext;
import org.apache.flink.api.connector.source.SplitsAssignment;

import java.io.IOException;
import java.util.Collections;

public class AggregationSplitEnumerator implements SplitEnumerator<AggregationSourceSplit, AggregationEnumeratorState> {
    private final SplitEnumeratorContext<AggregationSourceSplit> context;
    private boolean assigned;

    public AggregationSplitEnumerator(SplitEnumeratorContext<AggregationSourceSplit> context, boolean assigned) {
        this.context = context;
        this.assigned = assigned;
    }

    @Override
    public void start() {
    }

    @Override
    public void handleSplitRequest(int subtaskId, String requesterHostname) {
        assignIfNeeded(subtaskId);
    }

    @Override
    public void addSplitsBack(java.util.List<AggregationSourceSplit> splits, int subtaskId) {
        assigned = false;
    }

    @Override
    public void addReader(int subtaskId) {
        assignIfNeeded(subtaskId);
    }

    @Override
    public AggregationEnumeratorState snapshotState(long checkpointId) {
        return new AggregationEnumeratorState(assigned);
    }

    @Override
    public void close() throws IOException {
    }

    private void assignIfNeeded(int subtaskId) {
        if (assigned) {
            return;
        }
        context.assignSplits(new SplitsAssignment<AggregationSourceSplit>(
                Collections.singletonMap(subtaskId, Collections.singletonList(new AggregationSourceSplit("main")))));
        context.signalNoMoreSplits(subtaskId);
        assigned = true;
    }
}
