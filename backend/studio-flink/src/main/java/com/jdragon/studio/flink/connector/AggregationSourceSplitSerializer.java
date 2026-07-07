package com.jdragon.studio.flink.connector;

import org.apache.flink.core.io.SimpleVersionedSerializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class AggregationSourceSplitSerializer implements SimpleVersionedSerializer<AggregationSourceSplit> {
    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public byte[] serialize(AggregationSourceSplit split) throws IOException {
        return split.splitId().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public AggregationSourceSplit deserialize(int version, byte[] serialized) throws IOException {
        return new AggregationSourceSplit(new String(serialized, StandardCharsets.UTF_8));
    }
}
