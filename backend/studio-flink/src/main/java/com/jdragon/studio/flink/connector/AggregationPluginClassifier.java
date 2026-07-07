package com.jdragon.studio.flink.connector;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class AggregationPluginClassifier {
    private static final Set<String> QUEUE_TYPES = new HashSet<String>(Arrays.asList("kafka", "rocketmq", "rabbitmq"));

    private AggregationPluginClassifier() {
    }

    public static AggregationPluginKind classify(String pluginName) {
        String type = normalize(pluginName);
        if (QUEUE_TYPES.contains(type)) {
            return AggregationPluginKind.QUEUE;
        }
        if (type.contains("ftp") || type.contains("sftp") || type.contains("hdfs")
                || type.contains("minio") || type.contains("s3") || type.contains("oss")
                || type.equals("local") || type.equals("localfile")) {
            return AggregationPluginKind.FILE;
        }
        return AggregationPluginKind.STRUCTURED;
    }

    public static boolean isQueue(String pluginName) {
        return classify(pluginName) == AggregationPluginKind.QUEUE;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ENGLISH);
    }
}
