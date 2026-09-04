package com.jdragon.studio.infra.service;

import com.jdragon.studio.commons.exception.StudioErrorCode;
import com.jdragon.studio.commons.exception.StudioException;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.infra.config.StudioPlatformProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Service
public class ManagedRuntimeFileResolver {

    private final ManagedFileMaterializer materializer;
    private final ManagedFileService managedFileService;
    private final StudioPlatformProperties properties;
    private final ClusterInstanceIdentity identity;
    private final KerberosConfigRegistry kerberosConfigRegistry;
    private final ScheduledExecutorService heartbeatExecutor;

    public ManagedRuntimeFileResolver(ManagedFileMaterializer materializer,
                                      ManagedFileService managedFileService,
                                      StudioPlatformProperties properties,
                                      ClusterInstanceIdentity identity,
                                      KerberosConfigRegistry kerberosConfigRegistry) {
        this.materializer = materializer;
        this.managedFileService = managedFileService;
        this.properties = properties;
        this.identity = identity;
        this.kerberosConfigRegistry = kerberosConfigRegistry;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "studio-managed-file-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        };
        this.heartbeatExecutor = Executors.newScheduledThreadPool(1, factory);
    }

    public Resolution<Map<String, Object>> resolveMap(Map<String, Object> source,
                                                       String tenantId, Long projectId,
                                                       String consumerType, String consumerId) {
        if (source == null) {
            return new Resolution<Map<String, Object>>(new LinkedHashMap<String, Object>(),
                    new ArrayList<ManagedFileMaterializer.MaterializedFile>(),
                    new ArrayList<KerberosConfigRegistry.Activation>(), null, materializer);
        }
        if (!containsManagedFile(source)) {
            return new Resolution<Map<String, Object>>(deepCopyMap(source),
                    new ArrayList<ManagedFileMaterializer.MaterializedFile>(),
                    new ArrayList<KerberosConfigRegistry.Activation>(), null, materializer);
        }
        if (!managedPropertiesEnabled()) {
            throw new StudioException(StudioErrorCode.SERVICE_UNAVAILABLE,
                    "Managed file support is disabled on this runtime");
        }
        requireContext(tenantId, projectId);
        List<ManagedFileMaterializer.MaterializedFile> files = new ArrayList<ManagedFileMaterializer.MaterializedFile>();
        List<KerberosConfigRegistry.Activation> activations = new ArrayList<KerberosConfigRegistry.Activation>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resolved = (Map<String, Object>) resolveValue(source, tenantId, projectId,
                    consumerType, consumerId, files, activations, null, null);
            ScheduledFuture<?> heartbeat = scheduleHeartbeat(files);
            return new Resolution<Map<String, Object>>(resolved, files, activations, heartbeat, materializer);
        } catch (RuntimeException e) {
            closeActivations(activations);
            release(files);
            throw e;
        }
    }

    public Resolution<DataSourceDefinition> resolveDatasource(DataSourceDefinition source,
                                                               String consumerType, String consumerId) {
        if (source == null) {
            throw new IllegalArgumentException("Datasource definition is required");
        }
        validateManagedDatasourceFields(source.getTypeCode(), source.getTechnicalMetadata());
        Resolution<Map<String, Object>> metadata = resolveMap(source.getTechnicalMetadata(),
                source.getTenantId(), source.getProjectId(), consumerType, consumerId);
        DataSourceDefinition copy = new DataSourceDefinition();
        BeanUtils.copyProperties(source, copy);
        copy.setTechnicalMetadata(metadata.getValue());
        return new Resolution<DataSourceDefinition>(copy, metadata.files, metadata.activations,
                metadata.heartbeat, materializer);
    }

    public boolean containsManagedFiles(Object value) {
        return containsManagedFile(value);
    }

    private Object resolveValue(Object value, String tenantId, Long projectId,
                                String consumerType, String consumerId,
                                List<ManagedFileMaterializer.MaterializedFile> files,
                                List<KerberosConfigRegistry.Activation> activations,
                                String fieldKey,
                                KerberosRuntimeIdentity kerberosIdentity) {
        if (value instanceof Map<?, ?>) {
            KerberosRuntimeIdentity nestedIdentity = kerberosIdentity((Map<?, ?>) value, kerberosIdentity);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String nestedKey = String.valueOf(entry.getKey());
                result.put(nestedKey, resolveValue(entry.getValue(), tenantId, projectId,
                        consumerType, consumerId, files, activations, nestedKey, nestedIdentity));
            }
            if (!activations.isEmpty()) validatePrincipal(result);
            return result;
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                result.add(resolveValue(item, tenantId, projectId, consumerType, consumerId,
                        files, activations, fieldKey, kerberosIdentity));
            }
            return result;
        }
        if (ManagedFileService.isManagedFileUri(value)) {
            Long fileId = ManagedFileService.parseManagedFileId(value, "runtime configuration");
            ManagedFileMaterializer.MaterializedFile file = materializer.materialize(fileId,
                    tenantId, projectId, expectedPolicy(fieldKey),
                    consumerType, consumerId,
                    identity == null ? null : identity.instanceId());
            files.add(file);
            if (isKrb5Field(fieldKey)) {
                KerberosConfigRegistry.Activation activation = kerberosConfigRegistry.activate(
                        file.getPath(), file.getFile().getId(),
                        kerberosIdentity == null ? null : kerberosIdentity.principal,
                        kerberosIdentity == null ? null : kerberosIdentity.host);
                activations.add(activation);
                return activation.getMergedPath().toString();
            }
            return file.getPath().toString();
        }
        return value;
    }

    private KerberosRuntimeIdentity kerberosIdentity(Map<?, ?> values,
                                                      KerberosRuntimeIdentity inherited) {
        String principal = firstText(values.get("principal"), values.get("kerberosPrincipal"),
                inherited == null ? null : inherited.principal);
        String host = firstText(values.get("host"), values.get("bootstrap.servers"),
                firstText(values.get("endpoint"), values.get("brokers"),
                        inherited == null ? null : inherited.host));
        if (principal == null && host == null) return inherited;
        return new KerberosRuntimeIdentity(principal, host);
    }

    private String firstText(Object first, Object second, String fallback) {
        if (first != null && !String.valueOf(first).trim().isEmpty()) return String.valueOf(first).trim();
        if (second != null && !String.valueOf(second).trim().isEmpty()) return String.valueOf(second).trim();
        return fallback;
    }

    private boolean containsManagedFile(Object value) {
        if (ManagedFileService.isManagedFileUri(value)) return true;
        if (value instanceof Map<?, ?>) {
            for (Object item : ((Map<?, ?>) value).values()) if (containsManagedFile(item)) return true;
        } else if (value instanceof List<?>) {
            for (Object item : (List<?>) value) if (containsManagedFile(item)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopyMap(Map<String, Object> source) {
        return (Map<String, Object>) deepCopyValue(source);
    }

    private Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), deepCopyValue(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) result.add(deepCopyValue(item));
            return result;
        }
        return value;
    }

    private ScheduledFuture<?> scheduleHeartbeat(List<ManagedFileMaterializer.MaterializedFile> files) {
        if (files.isEmpty()) return null;
        int seconds = properties.getManagedFile() == null
                || properties.getManagedFile().getLeaseHeartbeatSeconds() == null
                || properties.getManagedFile().getLeaseHeartbeatSeconds().intValue() <= 0
                ? 60 : properties.getManagedFile().getLeaseHeartbeatSeconds().intValue();
        return heartbeatExecutor.scheduleAtFixedRate(() -> {
            for (ManagedFileMaterializer.MaterializedFile file : files) {
                try {
                    managedFileService.renewLease(file.getLease().getToken());
                } catch (Exception ignored) {
                    // The task will fail on its next file use; heartbeat must continue for other files.
                }
            }
        }, seconds, seconds, TimeUnit.SECONDS);
    }

    private void requireContext(String tenantId, Long projectId) {
        if (tenantId == null || tenantId.trim().isEmpty() || projectId == null) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Tenant and project context are required to resolve managed files");
        }
    }

    private void release(List<ManagedFileMaterializer.MaterializedFile> files) {
        for (ManagedFileMaterializer.MaterializedFile file : files) {
            try { materializer.release(file); }
            catch (Exception ignored) { }
        }
    }

    private void validateManagedDatasourceFields(String typeCode, Map<String, Object> metadata) {
        if (metadata == null || !managedPropertiesEnabled()) return;
        String normalizedType = typeCode == null ? "" : typeCode.trim().toLowerCase(Locale.ENGLISH);
        Set<String> fields;
        if ("kafka".equals(normalizedType)) {
            fields = Set.of("kerberosKeytabFilePath", "krb5Conf");
        } else if ("tbds-hdfs".equals(normalizedType) || "tbds-hdfs3".equals(normalizedType)) {
            fields = Set.of("hdfsSiteFilePath", "coreSiteFilePath", "kerberosKeytabFilePath", "krb5Conf");
        } else if ("tbds-hive3".equals(normalizedType)) {
            fields = Set.of("keytabPath", "krb5File");
        } else {
            return;
        }
        for (String field : fields) {
            Object value = metadata.get(field);
            if (value == null || String.valueOf(value).trim().isEmpty()) continue;
            if (!ManagedFileService.isManagedFileUri(value)) {
                throw new StudioException(StudioErrorCode.BAD_REQUEST,
                        "Datasource field " + field
                                + " must use an uploaded managed file; legacy local paths must be re-uploaded");
            }
        }
    }

    private boolean managedPropertiesEnabled() {
        return properties.getManagedFile() != null && properties.getManagedFile().isEnabled();
    }

    private void closeActivations(List<KerberosConfigRegistry.Activation> activations) {
        for (int index = activations.size() - 1; index >= 0; index--) {
            try { activations.get(index).close(); }
            catch (Exception ignored) { }
        }
    }

    private boolean isKrb5Field(String fieldKey) {
        return "krb5Conf".equals(fieldKey) || "krb5File".equals(fieldKey);
    }

    private String expectedPolicy(String fieldKey) {
        if ("kerberosKeytabFilePath".equals(fieldKey) || "keytabPath".equals(fieldKey)) {
            return "KERBEROS_KEYTAB";
        }
        if (isKrb5Field(fieldKey)) {
            return "KERBEROS_KRB5_CONF";
        }
        if ("hdfsSiteFilePath".equals(fieldKey) || "coreSiteFilePath".equals(fieldKey)) {
            return "HADOOP_SITE_XML";
        }
        return null;
    }

    private void validatePrincipal(Map<String, Object> values) {
        Object candidate = values.containsKey("principal") ? values.get("principal")
                : values.get("kerberosPrincipal");
        if (candidate == null || String.valueOf(candidate).trim().isEmpty()) return;
        String principal = String.valueOf(candidate).trim();
        int separator = principal.lastIndexOf('@');
        if (separator <= 0 || separator == principal.length() - 1) {
            throw new StudioException(StudioErrorCode.BAD_REQUEST,
                    "Kerberos Principal must include an explicit @REALM when managed krb5.conf is used");
        }
    }

    @PreDestroy
    public void close() {
        heartbeatExecutor.shutdownNow();
    }

    public static final class Resolution<T> implements AutoCloseable {
        private final T value;
        private final List<ManagedFileMaterializer.MaterializedFile> files;
        private final List<KerberosConfigRegistry.Activation> activations;
        private final ScheduledFuture<?> heartbeat;
        private final ManagedFileMaterializer materializer;
        private boolean closed;

        private Resolution(T value, List<ManagedFileMaterializer.MaterializedFile> files,
                           List<KerberosConfigRegistry.Activation> activations,
                           ScheduledFuture<?> heartbeat, ManagedFileMaterializer materializer) {
            this.value = value;
            this.files = files;
            this.activations = activations;
            this.heartbeat = heartbeat;
            this.materializer = materializer;
        }

        public T getValue() { return value; }
        public List<ManagedFileMaterializer.MaterializedFile> getFiles() { return new ArrayList<>(files); }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if (heartbeat != null) heartbeat.cancel(false);
            for (int index = activations.size() - 1; index >= 0; index--) {
                try { activations.get(index).close(); }
                catch (Exception ignored) { }
            }
            for (ManagedFileMaterializer.MaterializedFile file : files) {
                try { materializer.release(file); }
                catch (Exception ignored) { }
            }
        }
    }

    private static final class KerberosRuntimeIdentity {
        private final String principal;
        private final String host;

        private KerberosRuntimeIdentity(String principal, String host) {
            this.principal = principal;
            this.host = host;
        }
    }
}
