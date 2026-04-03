package com.jdragon.studio.core.spi;

import com.jdragon.studio.dto.model.dto.ExecutionEvent;

public interface ExecutionEventPublisher {
    void publish(ExecutionEvent event);
}

