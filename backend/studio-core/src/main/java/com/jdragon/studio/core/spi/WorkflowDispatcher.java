package com.jdragon.studio.core.spi;

public interface WorkflowDispatcher {
    void dispatchReadyNodes();

    void triggerManualRun(Long workflowDefinitionId);
}
