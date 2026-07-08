package com.jdragon.studio.flink.connector;

import org.apache.flink.api.connector.source.ReaderOutput;
import org.apache.flink.api.connector.source.SourceReader;
import org.apache.flink.api.connector.source.SourceEvent;
import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.core.io.InputStatus;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.DataType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class AggregationSourceReader implements SourceReader<RowData, AggregationSourceSplit> {
    private final AggregationRuntimeHandle runtimeHandle;
    private final Integer maxRows;
    private final DataType producedDataType;
    private final Boundedness boundedness;
    private final LinkedBlockingQueue<RowData> rows = new LinkedBlockingQueue<RowData>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicInteger emitted = new AtomicInteger(0);
    private volatile CompletableFuture<Void> available = new CompletableFuture<Void>();
    private volatile boolean finished;
    private volatile Throwable error;
    private Thread worker;

    public AggregationSourceReader(AggregationRuntimeHandle runtimeHandle, Integer maxRows, DataType producedDataType, Boundedness boundedness) {
        this.runtimeHandle = runtimeHandle;
        this.maxRows = maxRows;
        this.producedDataType = producedDataType;
        this.boundedness = boundedness;
    }

    @Override
    public void start() {
    }

    @Override
    public InputStatus pollNext(ReaderOutput<RowData> output) throws Exception {
        if (error != null) {
            throw new RuntimeException("DataAggregation source failed", error);
        }
        RowData row = rows.poll();
        if (row != null) {
            output.collect(row);
            return rows.isEmpty() ? InputStatus.NOTHING_AVAILABLE : InputStatus.MORE_AVAILABLE;
        }
        if (finished && boundedness == Boundedness.BOUNDED) {
            return InputStatus.END_OF_INPUT;
        }
        resetAvailableIfNeeded();
        return InputStatus.NOTHING_AVAILABLE;
    }

    @Override
    public List<AggregationSourceSplit> snapshotState(long checkpointId) {
        return new ArrayList<AggregationSourceSplit>();
    }

    @Override
    public CompletableFuture<Void> isAvailable() {
        if (!rows.isEmpty() || finished || error != null) {
            return CompletableFuture.completedFuture(null);
        }
        return available;
    }

    @Override
    public void addSplits(List<AggregationSourceSplit> splits) {
        if (splits == null || splits.isEmpty()) {
            return;
        }
        if (started.compareAndSet(false, true)) {
            worker = new Thread(this::runSource, "dataaggregation-flink-source-" + runtimeHandle.summary());
            worker.setDaemon(true);
            worker.start();
        }
    }

    @Override
    public void notifyNoMoreSplits() {
        if (!started.get() && boundedness == Boundedness.BOUNDED) {
            finished = true;
            completeAvailable();
        }
    }

    @Override
    public void handleSourceEvents(SourceEvent sourceEvent) {
    }

    @Override
    public void close() {
        closed.set(true);
        if (worker != null) {
            worker.interrupt();
        }
        completeAvailable();
    }

    private void runSource() {
        AggregationFlinkTableRuntime runtime = null;
        try {
            runtime = AggregationRuntimeResolver.resolve(runtimeHandle);
            runtime.setMaxRows(maxRows == null ? runtime.getMaxRows() : maxRows);
            runtime.setProducedDataType(producedDataType);
            runtime.setFieldNames(DataType.getFieldNames(producedDataType));
            final AggregationFlinkTableRuntime activeRuntime = runtime;
            AggregationSourceStrategy strategy = AggregationSourceStrategyFactory.create(runtime.getPluginName());
            AggregationRowDataConverter converter = new AggregationRowDataConverter(producedDataType);
            strategy.readRows(activeRuntime, row -> {
                if (closed.get()) {
                    return false;
                }
                int limit = activeRuntime.getMaxRows() == null ? 0 : activeRuntime.getMaxRows();
                if (limit > 0 && emitted.get() >= limit) {
                    return false;
                }
                rows.offer(converter.convert(row));
                emitted.incrementAndGet();
                completeAvailable();
                return true;
            });
        } catch (StopSourceScanException ignored) {
        } catch (Throwable t) {
            error = t;
        } finally {
            if (runtime != null) {
                try {
                    AggregationRuntimeResolver.updateAudit(runtimeHandle, runtime);
                } catch (Throwable ignored) {
                    // query results should not be discarded only because audit write-back failed
                }
            }
            finished = true;
            completeAvailable();
        }
    }

    private void resetAvailableIfNeeded() {
        if (available.isDone() && rows.isEmpty() && !finished && error == null) {
            available = new CompletableFuture<Void>();
        }
    }

    private void completeAvailable() {
        CompletableFuture<Void> current = available;
        if (!current.isDone()) {
            current.complete(null);
        }
    }
}
