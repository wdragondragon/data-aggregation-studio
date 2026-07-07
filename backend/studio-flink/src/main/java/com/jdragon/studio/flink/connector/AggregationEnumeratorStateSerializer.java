package com.jdragon.studio.flink.connector;

import org.apache.flink.core.io.SimpleVersionedSerializer;

import java.io.IOException;

public class AggregationEnumeratorStateSerializer implements SimpleVersionedSerializer<AggregationEnumeratorState> {
    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public byte[] serialize(AggregationEnumeratorState state) throws IOException {
        return new byte[]{(byte) (state != null && state.isAssigned() ? 1 : 0)};
    }

    @Override
    public AggregationEnumeratorState deserialize(int version, byte[] serialized) throws IOException {
        return new AggregationEnumeratorState(serialized != null && serialized.length > 0 && serialized[0] == 1);
    }
}
