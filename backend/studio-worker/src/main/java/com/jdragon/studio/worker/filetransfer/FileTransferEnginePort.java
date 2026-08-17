package com.jdragon.studio.worker.filetransfer;

import com.jdragon.aggregation.datasource.file.transfer.TransferFileSystem;
import com.jdragon.aggregation.transfer.PreparedTransfer;
import com.jdragon.aggregation.transfer.TransferCheckpointStore;
import com.jdragon.aggregation.transfer.TransferControl;
import com.jdragon.aggregation.transfer.TransferEventListener;
import com.jdragon.aggregation.transfer.TransferFileSystemFactory;
import com.jdragon.aggregation.transfer.model.TransferResult;
import com.jdragon.aggregation.transfer.model.TransferSpec;

import java.io.IOException;
import java.time.Instant;
import java.util.function.UnaryOperator;

public interface FileTransferEnginePort {

    PreparedTransfer prepare(TransferSpec spec,
                             TransferFileSystem source,
                             TransferFileSystem target,
                             String runId,
                             Instant plannedAt,
                             TransferEventListener listener) throws IOException;

    TransferResult execute(PreparedTransfer prepared,
                           TransferFileSystemFactory sourceFactory,
                           TransferFileSystemFactory targetFactory,
                           TransferCheckpointStore checkpointStore,
                           TransferEventListener listener,
                           TransferControl control,
                           UnaryOperator<Runnable> taskDecorator) throws InterruptedException;
}
