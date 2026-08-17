package com.jdragon.studio.worker.filetransfer;

import com.jdragon.aggregation.datasource.file.transfer.TransferFileSystem;
import com.jdragon.aggregation.transfer.PreparedTransfer;
import com.jdragon.aggregation.transfer.TransferCheckpointStore;
import com.jdragon.aggregation.transfer.TransferControl;
import com.jdragon.aggregation.transfer.TransferEngine;
import com.jdragon.aggregation.transfer.TransferEventListener;
import com.jdragon.aggregation.transfer.TransferFileSystemFactory;
import com.jdragon.aggregation.transfer.TransferPlanner;
import com.jdragon.aggregation.transfer.model.TransferResult;
import com.jdragon.aggregation.transfer.model.TransferSpec;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.function.UnaryOperator;

@Component
public class DataAggregationFileTransferEngineAdapter implements FileTransferEnginePort {

    @Override
    public PreparedTransfer prepare(TransferSpec spec,
                                    TransferFileSystem source,
                                    TransferFileSystem target,
                                    String runId,
                                    Instant plannedAt,
                                    TransferEventListener listener) throws IOException {
        return new TransferPlanner().prepare(spec, source, target, runId, plannedAt, listener);
    }

    @Override
    public TransferResult execute(PreparedTransfer prepared,
                                  TransferFileSystemFactory sourceFactory,
                                  TransferFileSystemFactory targetFactory,
                                  TransferCheckpointStore checkpointStore,
                                  TransferEventListener listener,
                                  TransferControl control,
                                  UnaryOperator<Runnable> taskDecorator) throws InterruptedException {
        return new TransferEngine(taskDecorator).execute(prepared, sourceFactory, targetFactory,
                checkpointStore, listener, control);
    }
}
