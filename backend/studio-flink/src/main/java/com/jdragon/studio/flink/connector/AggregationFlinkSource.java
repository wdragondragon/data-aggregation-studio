package com.jdragon.studio.flink.connector;

import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.api.connector.source.Source;
import org.apache.flink.api.connector.source.SourceReader;
import org.apache.flink.api.connector.source.SourceReaderContext;
import org.apache.flink.api.connector.source.SplitEnumerator;
import org.apache.flink.api.connector.source.SplitEnumeratorContext;
import org.apache.flink.core.io.SimpleVersionedSerializer;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.DataType;

public class AggregationFlinkSource implements Source<RowData, AggregationSourceSplit, AggregationEnumeratorState> {
    private final String runtimeRef;
    private final String pluginName;
    private final String scanMode;
    private final Integer maxRows;
    private final DataType producedDataType;

    public AggregationFlinkSource(String runtimeRef,
                                  String pluginName,
                                  String scanMode,
                                  Integer maxRows,
                                  DataType producedDataType) {
        this.runtimeRef = runtimeRef;
        this.pluginName = pluginName;
        this.scanMode = scanMode;
        this.maxRows = maxRows;
        this.producedDataType = producedDataType;
    }

    @Override
    public Boundedness getBoundedness() {
        return AggregationPluginClassifier.isQueue(pluginName)
                ? Boundedness.CONTINUOUS_UNBOUNDED
                : Boundedness.BOUNDED;
    }

    @Override
    public SourceReader<RowData, AggregationSourceSplit> createReader(SourceReaderContext readerContext) {
        return new AggregationSourceReader(runtimeRef, maxRows, producedDataType, getBoundedness());
    }

    @Override
    public SplitEnumerator<AggregationSourceSplit, AggregationEnumeratorState> createEnumerator(
            SplitEnumeratorContext<AggregationSourceSplit> enumContext) {
        return new AggregationSplitEnumerator(enumContext, false);
    }

    @Override
    public SplitEnumerator<AggregationSourceSplit, AggregationEnumeratorState> restoreEnumerator(
            SplitEnumeratorContext<AggregationSourceSplit> enumContext,
            AggregationEnumeratorState checkpoint) {
        return new AggregationSplitEnumerator(enumContext, checkpoint != null && checkpoint.isAssigned());
    }

    @Override
    public SimpleVersionedSerializer<AggregationSourceSplit> getSplitSerializer() {
        return new AggregationSourceSplitSerializer();
    }

    @Override
    public SimpleVersionedSerializer<AggregationEnumeratorState> getEnumeratorCheckpointSerializer() {
        return new AggregationEnumeratorStateSerializer();
    }
}
