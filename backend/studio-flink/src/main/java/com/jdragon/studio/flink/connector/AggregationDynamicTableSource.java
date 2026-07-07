package com.jdragon.studio.flink.connector;

import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.source.LookupTableSource;
import org.apache.flink.table.connector.source.ScanTableSource;
import org.apache.flink.table.connector.source.SourceProvider;
import org.apache.flink.table.connector.source.abilities.SupportsLimitPushDown;
import org.apache.flink.table.connector.source.abilities.SupportsProjectionPushDown;
import org.apache.flink.table.legacy.connector.source.TableFunctionProvider;
import org.apache.flink.table.types.DataType;

import java.util.Locale;

public class AggregationDynamicTableSource implements ScanTableSource, LookupTableSource,
        SupportsProjectionPushDown, SupportsLimitPushDown {
    private final String runtimeRef;
    private final String pluginName;
    private final String scanMode;
    private Integer maxRows;
    private DataType producedDataType;

    public AggregationDynamicTableSource(String runtimeRef,
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
    public ChangelogMode getChangelogMode() {
        return ChangelogMode.insertOnly();
    }

    @Override
    public ScanRuntimeProvider getScanRuntimeProvider(ScanContext runtimeProviderContext) {
        return SourceProvider.of(new AggregationFlinkSource(runtimeRef, pluginName, scanMode, maxRows, producedDataType));
    }

    @Override
    public LookupRuntimeProvider getLookupRuntimeProvider(LookupContext context) {
        if (AggregationPluginClassifier.classify(pluginName) != AggregationPluginKind.STRUCTURED) {
            throw new UnsupportedOperationException("Lookup source only supports structured DataAggregation plugins");
        }
        return TableFunctionProvider.of(new AggregationLookupFunction(runtimeRef, context.getKeys(), producedDataType));
    }

    @Override
    public ScanTableSource copy() {
        return new AggregationDynamicTableSource(runtimeRef, pluginName, scanMode, maxRows, producedDataType);
    }

    @Override
    public String asSummaryString() {
        return "DataAggregation Source";
    }

    @Override
    public boolean supportsNestedProjection() {
        return false;
    }

    @Override
    public void applyProjection(int[][] projectedFields, DataType producedDataType) {
        this.producedDataType = producedDataType;
    }

    @Override
    public void applyLimit(long limit) {
        if (limit > 0 && limit < Integer.MAX_VALUE) {
            this.maxRows = (int) limit;
            AggregationFlinkRuntimeRegistry.required(runtimeRef).setMaxRows(this.maxRows);
        }
    }

    private boolean isUnbounded() {
        return "unbounded".equalsIgnoreCase(scanMode)
                || AggregationPluginClassifier.isQueue(pluginName)
                || "continuous".equals(scanMode == null ? "" : scanMode.toLowerCase(Locale.ENGLISH));
    }
}
