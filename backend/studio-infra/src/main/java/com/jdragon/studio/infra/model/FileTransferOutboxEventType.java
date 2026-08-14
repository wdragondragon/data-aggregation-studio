package com.jdragon.studio.infra.model;

public enum FileTransferOutboxEventType {
    RUN_CREATED,
    RUN_CHANGED,
    ITEM_CHANGED,
    RUN_REMOVED,
    ITEM_REMOVED
}
