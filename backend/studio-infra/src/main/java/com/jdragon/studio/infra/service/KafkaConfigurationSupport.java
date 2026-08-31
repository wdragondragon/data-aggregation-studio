package com.jdragon.studio.infra.service;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Defines the ownership boundary between Kafka datasource and task options. */
public final class KafkaConfigurationSupport {

    private static final Set<String> TASK_OPTION_KEYS = new LinkedHashSet<String>(Arrays.asList(
            "topic", "queue", "queuename", "tag",
            "group.id", "groupid", "consumergroup",
            "offsetreset", "resetoffset", "polltimeoutms", "batchsize",
            "keepreadtime", "retrypoll", "parsingrules", "fielddelimiter",
            "ack", "acks", "retries", "autocreatetopic",
            "createtopicnumpartition", "createtopicreplicationfactor", "writetype",
            "otherproperties", "columns", "sourcealias"));
    private static final Set<String> DATASOURCE_CONNECTION_KEYS = new LinkedHashSet<String>(Arrays.asList(
            "bootstrap.servers", "bootstrapservers", "brokers",
            "username", "password", "kerberos", "principal",
            "kerberoskeytabfilepath", "krb5conf", "kerberosdomain",
            "security", "sasl", "ssl", "client", "connections", "request",
            "retry", "reconnect", "metadata", "fetch", "receive", "send",
            "max", "enable", "auto", "isolation", "partition", "allow",
            "interceptor", "metrics", "metric"));
    private static final String[] DATASOURCE_CONNECTION_PREFIXES = {
            "security.", "sasl.", "ssl.", "client.", "connections.",
            "request.", "retry.", "reconnect.", "metadata.", "fetch.",
            "receive.", "send.", "max.", "enable.", "auto.", "isolation.",
            "partition.", "allow.", "interceptor.", "metrics.", "metric.", "kerberos."
    };

    private KafkaConfigurationSupport() {
    }

    public static Map<String, Object> normalizeDatasourceMetadata(Map<String, Object> metadata) {
        Map<String, Object> normalized = metadata == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(metadata);
        copyIfMissing(normalized, "bootstrap.servers", "bootstrapServers");
        copyIfMissing(normalized, "bootstrap.servers", "brokers");
        copyIfMissing(normalized, "username", "userName");
        removeTaskOptions(normalized);
        normalized.remove("brokers");
        normalized.remove("bootstrapServers");
        normalized.remove("userName");
        return normalized;
    }

    public static Map<String, Object> toJobPluginConnectionConfig(Map<String, Object> metadata) {
        Map<String, Object> plugin = normalizeDatasourceMetadata(metadata);
        Object bootstrapServers = plugin.remove("bootstrap.servers");
        if (hasValue(bootstrapServers)) {
            plugin.put("bootstrapServers", bootstrapServers);
        }
        return plugin;
    }

    /** Keeps datasource-owned connection properties out of task runtime options. */
    public static Map<String, Object> normalizeTaskRuntimeOptions(Map<String, Object> options) {
        Map<String, Object> normalized = options == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(options);
        Iterator<String> iterator = normalized.keySet().iterator();
        while (iterator.hasNext()) {
            String key = iterator.next();
            if (isDatasourceConnectionKey(key)) {
                iterator.remove();
            }
        }
        return normalized;
    }

    private static void removeTaskOptions(Map<String, Object> metadata) {
        Iterator<String> iterator = metadata.keySet().iterator();
        while (iterator.hasNext()) {
            String key = iterator.next();
            if (key != null && TASK_OPTION_KEYS.contains(key.trim().toLowerCase(Locale.ROOT))) {
                iterator.remove();
            }
        }
    }

    private static void copyIfMissing(Map<String, Object> target, String key, String alias) {
        if (hasValue(target.get(key))) {
            return;
        }
        Object value = target.get(alias);
        if (hasValue(value)) {
            target.put(key, value);
        }
    }

    private static boolean isDatasourceConnectionKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        if (DATASOURCE_CONNECTION_KEYS.contains(normalized)) {
            return true;
        }
        for (String prefix : DATASOURCE_CONNECTION_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasValue(Object value) {
        return value != null && (!(value instanceof String) || !((String) value).trim().isEmpty());
    }
}
