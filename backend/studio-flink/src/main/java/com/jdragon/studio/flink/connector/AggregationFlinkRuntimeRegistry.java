package com.jdragon.studio.flink.connector;

import com.jdragon.aggregation.datasource.SourcePluginType;
import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeSession;
import com.jdragon.aggregation.pluginloader.runtime.ResolvedPlugin;
import com.jdragon.aggregation.pluginloader.type.IPluginType;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AggregationFlinkRuntimeRegistry {
    public static final String CAPABILITY_TOKEN_HEADER = "X-Studio-Flink-Runtime-Token";
    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<String, Entry>();
    private static final ScheduledExecutorService EXPIRY_REAPER = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "DataAggregation-FlinkRuntime-Reaper");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private AggregationFlinkRuntimeRegistry() {
    }

    public static String register(AggregationFlinkTableRuntime runtime, int ttlSeconds) {
        return register(runtime, ttlSeconds, null, Collections.emptyMap(), null);
    }

    public static String register(AggregationFlinkTableRuntime runtime, int ttlSeconds,
                                  Map<Long, Path> managedFiles, AutoCloseable lifecycle) {
        return register(runtime, ttlSeconds, null, managedFiles, lifecycle);
    }

    /**
     * Registers a remote connector capability and pins its source-plugin revision immediately.
     * The pin is released when the capability expires or is explicitly removed.
     */
    public static String registerCapability(AggregationFlinkTableRuntime runtime, int ttlSeconds) {
        PluginRuntimeSession session = PluginRuntimeSession.createDetached();
        try {
            String pluginName = requirePluginName(runtime);
            ResolvedPlugin plugin = session.resolve(SourcePluginType.SOURCE, pluginName);
            return register(runtime, ttlSeconds, new PluginPin(session, plugin),
                    Collections.emptyMap(), null);
        } catch (RuntimeException ex) {
            session.close();
            throw ex;
        }
    }

    /**
     * Registers a capability for a plugin revision already selected by the owning task.
     * A dedicated lease keeps the exact revision alive for this capability without
     * re-reading the current plugin pointer.
     */
    public static String registerCapability(AggregationFlinkTableRuntime runtime, int ttlSeconds,
                                            ResolvedPlugin selectedPlugin) {
        return registerCapability(runtime, ttlSeconds, selectedPlugin, Collections.emptyMap(), null);
    }

    public static String registerCapability(AggregationFlinkTableRuntime runtime, int ttlSeconds,
                                            ResolvedPlugin selectedPlugin,
                                            Map<Long, Path> managedFiles, AutoCloseable lifecycle) {
        String pluginName = requirePluginName(runtime);
        String expectedCoordinate = SourcePluginType.SOURCE.getName() + "/" + pluginName;
        if (selectedPlugin == null
                || !SourcePluginType.SOURCE.getName().equals(selectedPlugin.getPluginType().getName())
                || !pluginName.equals(selectedPlugin.getPluginName())
                || !expectedCoordinate.equals(selectedPlugin.getCoordinate())) {
            throw new IllegalArgumentException("Selected plugin does not match the Flink runtime");
        }
        PluginRuntimeSession capabilitySession = PluginRuntimeSession.createDetached();
        try {
            capabilitySession.acquire(selectedPlugin);
            return register(runtime, ttlSeconds, new PluginPin(capabilitySession, selectedPlugin),
                    managedFiles, lifecycle);
        } catch (RuntimeException ex) {
            capabilitySession.close();
            throw ex;
        }
    }

    private static String register(AggregationFlinkTableRuntime runtime, int ttlSeconds, PluginPin pin,
                                   Map<Long, Path> managedFiles, AutoCloseable lifecycle) {
        String ref = UUID.randomUUID().toString();
        runtime.setRuntimeRef(ref);
        long expiresAt = Instant.now().toEpochMilli() + Math.max(30, ttlSeconds) * 1000L;
        Entry entry = new Entry(runtime, expiresAt, pin, managedFiles, lifecycle);
        ENTRIES.put(ref, entry);
        EXPIRY_REAPER.schedule(() -> remove(ref, entry), Math.max(1L, expiresAt - Instant.now().toEpochMilli()),
                TimeUnit.MILLISECONDS);
        return ref;
    }

    public static AggregationFlinkTableRuntime required(String ref) {
        return requiredEntry(ref).runtime;
    }

    public static boolean isValid(String ref) {
        if (ref == null || ref.trim().isEmpty()) {
            return false;
        }
        Entry entry = ENTRIES.get(ref);
        if (entry == null) {
            return false;
        }
        if (entry.expiresAt < Instant.now().toEpochMilli()) {
            remove(ref, entry);
            return false;
        }
        return true;
    }

    public static AggregationFlinkTableRuntimePayload resolvePayload(String ref) {
        return AggregationFlinkTableRuntimePayload.fromRuntime(required(ref));
    }

    public static void updateAudit(String ref, AggregationFlinkTableRuntimePayload payload) {
        if (payload == null) {
            return;
        }
        payload.mergeAuditInto(required(ref));
    }

    public static void remove(String ref) {
        if (ref != null) {
            Entry entry = ENTRIES.remove(ref);
            if (entry != null) {
                entry.close();
            }
        }
    }

    /**
     * Acquires a short operation lease for the source plugin pinned when a remote capability was issued.
     * It never re-resolves the current plugin pointer.
     */
    public static PluginArtifactLease acquirePinnedPlugin(String ref, IPluginType pluginType, String pluginName) {
        Entry entry = requiredEntry(ref);
        return entry.acquirePinnedPlugin(pluginType, pluginName);
    }

    public static Path requiredManagedFile(String ref, Long fileId) {
        if (fileId == null) {
            throw new IllegalArgumentException("Managed file id is required");
        }
        Path path = requiredEntry(ref).managedFiles.get(fileId);
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalStateException("Managed file is not available to this Flink runtime capability");
        }
        return path;
    }

    private static Entry requiredEntry(String ref) {
        Entry entry = ENTRIES.get(ref);
        if (entry == null || entry.expiresAt < Instant.now().toEpochMilli()) {
            if (entry != null) {
                remove(ref, entry);
            }
            throw new IllegalStateException("DataAggregation Flink runtime ref expired or missing");
        }
        return entry;
    }

    private static void remove(String ref, Entry expected) {
        if (ENTRIES.remove(ref, expected)) {
            expected.close();
        }
    }

    private static String requirePluginName(AggregationFlinkTableRuntime runtime) {
        if (runtime == null || runtime.getPluginName() == null || runtime.getPluginName().trim().isEmpty()) {
            throw new IllegalArgumentException("DataAggregation Flink runtime plugin is required for a remote capability");
        }
        return runtime.getPluginName().trim();
    }

    private static class Entry {
        private final AggregationFlinkTableRuntime runtime;
        private final long expiresAt;
        private final PluginPin pin;
        private final Map<Long, Path> managedFiles;
        private final AutoCloseable lifecycle;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Entry(AggregationFlinkTableRuntime runtime, long expiresAt, PluginPin pin,
                      Map<Long, Path> managedFiles, AutoCloseable lifecycle) {
            this.runtime = runtime;
            this.expiresAt = expiresAt;
            this.pin = pin;
            this.managedFiles = managedFiles == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<Long, Path>(managedFiles));
            this.lifecycle = lifecycle;
        }

        private synchronized PluginArtifactLease acquirePinnedPlugin(IPluginType pluginType, String pluginName) {
            if (closed.get()) {
                throw new IllegalStateException("DataAggregation Flink runtime ref expired or missing");
            }
            if (pin == null) {
                throw new IllegalStateException("DataAggregation Flink runtime capability has no pinned plugin artifact");
            }
            String expectedPluginName = requirePluginName(runtime);
            if (!SourcePluginType.SOURCE.getName().equals(pluginType.getName())
                    || !expectedPluginName.equals(pluginName)
                    || !pin.plugin.getCoordinate().equals(pluginType.getName() + "/" + pluginName)) {
                throw new IllegalArgumentException("Requested plugin does not match the Flink runtime");
            }
            PluginRuntimeSession operationSession = PluginRuntimeSession.createDetached();
            try {
                operationSession.acquire(pin.plugin);
                return new PluginArtifactLease(pin.plugin, operationSession);
            } catch (RuntimeException ex) {
                operationSession.close();
                throw ex;
            }
        }

        private void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    if (pin != null) pin.close();
                } finally {
                    if (lifecycle != null) {
                        try {
                            lifecycle.close();
                        } catch (Exception ignored) {
                            // Runtime cleanup is best effort after the capability is no longer usable.
                        }
                    }
                }
            }
        }
    }

    private static final class PluginPin implements AutoCloseable {
        private final PluginRuntimeSession session;
        private final ResolvedPlugin plugin;

        private PluginPin(PluginRuntimeSession session, ResolvedPlugin plugin) {
            this.session = session;
            this.plugin = plugin;
        }

        @Override
        public void close() {
            session.close();
        }
    }

    public static final class PluginArtifactLease implements AutoCloseable {
        private final ResolvedPlugin plugin;
        private final PluginRuntimeSession session;

        private PluginArtifactLease(ResolvedPlugin plugin, PluginRuntimeSession session) {
            this.plugin = plugin;
            this.session = session;
        }

        public ResolvedPlugin getPlugin() {
            return plugin;
        }

        @Override
        public void close() {
            session.close();
        }
    }
}
