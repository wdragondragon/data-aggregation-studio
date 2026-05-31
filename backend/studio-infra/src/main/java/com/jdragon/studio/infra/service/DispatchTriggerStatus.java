package com.jdragon.studio.infra.service;

public enum DispatchTriggerStatus {
    TRIGGERED,
    SKIPPED_ACTIVE,
    SKIPPED_NO_WORKER,
    LOCK_BUSY
}
