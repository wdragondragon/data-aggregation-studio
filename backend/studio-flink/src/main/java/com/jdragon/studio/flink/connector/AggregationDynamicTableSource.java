package com.jdragon.studio.flink.connector;

import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.source.LookupTableSource;
import org.apache.flink.table.connector.source.ScanTableSource;
import org.apache.flink.table.connector.source.SourceProvider;
import org.apache.flink.table.connector.source.abilities.SupportsFilterPushDown;
import org.apache.flink.table.connector.source.abilities.SupportsProjectionPushDown;
import org.apache.flink.table.expressions.ResolvedExpression;
import org.apache.flink.table.legacy.connector.source.TableFunctionProvider;
import org.apache.flink.table.types.DataType;

import java.util.List;
import java.util.Locale;

public class AggregationDynamicTableSource implements ScanTableSource, LookupTableSource,
        SupportsProjectionPushDown, SupportsFilterPushDown {
    private final AggregationRuntimeHandle runtimeHandle;
    private final String pluginName;
    private final String scanMode;
    private Integer maxRows;
    private DataType producedDataType;

    public AggregationDynamicTableSource(AggregationRuntimeHandle runtimeHandle,
                                         String pluginName,
                                         String scanMode,
                                         Integer maxRows,
                                         DataType producedDataType) {
        this.runtimeHandle = runtimeHandle;
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
        return SourceProvider.of(new AggregationFlinkSource(runtimeHandle, pluginName, scanMode, maxRows, producedDataType));
    }

    @Override
    public LookupRuntimeProvider getLookupRuntimeProvider(LookupContext context) {
        AggregationPluginKind pluginKind = AggregationPluginClassifier.classify(pluginName);
        if (pluginKind != AggregationPluginKind.STRUCTURED && pluginKind != AggregationPluginKind.HTTP) {
            throw new UnsupportedOperationException(
                    "Lookup source only supports structured and HTTP DataAggregation plugins");
        }
        return TableFunctionProvider.of(new AggregationLookupFunction(runtimeHandle, context.getKeys(), producedDataType));
    }

    @Override
    public ScanTableSource copy() {
        return new AggregationDynamicTableSource(runtimeHandle.copy(), pluginName, scanMode, maxRows, producedDataType);
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
    public Result applyFilters(List<ResolvedExpression> filters) {
        AggregationFlinkTableRuntime runtime = AggregationRuntimeResolver.resolve(runtimeHandle);
        AggregationFilterPushDownTranslator.Translation translation =
                AggregationFilterPushDownTranslator.translate(filters, runtime,
                        AggregationPluginClassifier.classify(pluginName));
        runtime.setPushedFilters(translation.getPushedFilterSql());
        runtime.setRemainingFilters(translation.getRemainingFilterSql());
        runtime.setPathContextFilters(translation.getPathContextFilters());
        runtime.setHttpPushdownFilters(translation.getHttpPushdownFilters());
        runtime.setHttpFilterAlwaysFalse(translation.isHttpFilterAlwaysFalse());
        AggregationRuntimeResolver.captureRuntimeState(runtimeHandle, runtime);
        AggregationRuntimeResolver.updateAudit(runtimeHandle, runtime);
        return Result.of(translation.getAcceptedFilters(), translation.getRemainingFilters());
    }

    private boolean isUnbounded() {
        return "unbounded".equalsIgnoreCase(scanMode)
                || AggregationPluginClassifier.isQueue(pluginName)
                || "continuous".equals(scanMode == null ? "" : scanMode.toLowerCase(Locale.ENGLISH));
    }
}
