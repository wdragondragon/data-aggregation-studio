package com.jdragon.studio.worker.filetransfer;

import com.jdragon.aggregation.transfer.TransferCanceledException;
import com.jdragon.aggregation.transfer.TransferControl;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.mapper.FileTransferRunMapper;

final class DatabaseTransferControl extends TransferControl {

    private final Long runId;
    private final FileTransferRunMapper runMapper;

    DatabaseTransferControl(Long runId, FileTransferRunMapper runMapper) {
        this.runId = runId;
        this.runMapper = runMapper;
    }

    @Override
    public boolean isPauseRequested() {
        return "PAUSED".equalsIgnoreCase(status());
    }

    @Override
    public boolean isCanceled() {
        return "CANCELED".equalsIgnoreCase(status());
    }

    @Override
    public void awaitIfPaused() throws InterruptedException, TransferCanceledException {
        while (isPauseRequested()) {
            Thread.sleep(500L);
            if (isCanceled()) {
                throw new TransferCanceledException();
            }
        }
        if (isCanceled()) {
            throw new TransferCanceledException();
        }
    }

    private String status() {
        FileTransferRunEntity run = runMapper.selectById(runId);
        return run == null ? "CANCELED" : run.getStatus();
    }
}
