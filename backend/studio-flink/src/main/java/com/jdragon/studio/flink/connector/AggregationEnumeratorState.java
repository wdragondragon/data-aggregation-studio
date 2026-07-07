package com.jdragon.studio.flink.connector;

import java.io.Serializable;

public class AggregationEnumeratorState implements Serializable {
    private boolean assigned;

    public AggregationEnumeratorState() {
    }

    public AggregationEnumeratorState(boolean assigned) {
        this.assigned = assigned;
    }

    public boolean isAssigned() {
        return assigned;
    }
}
