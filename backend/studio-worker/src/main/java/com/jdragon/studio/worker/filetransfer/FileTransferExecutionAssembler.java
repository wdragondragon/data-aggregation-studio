package com.jdragon.studio.worker.filetransfer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jdragon.aggregation.transfer.TransferFileSystemFactory;
import com.jdragon.aggregation.transfer.TransferPaths;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.infra.entity.FileTransferRunEntity;
import com.jdragon.studio.infra.service.DataSourceService;
import com.jdragon.studio.infra.service.DatasourceClusterBindingService;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class FileTransferExecutionAssembler {

    private final DataSourceService dataSourceService;
    private final DatasourceClusterBindingService datasourceClusterBindingService;
    private final AggregationSourceCapabilityProvider sourceCapabilityProvider;
    private final ObjectMapper objectMapper;

    FileTransferExecutionAssembler(DataSourceService dataSourceService,
                                   DatasourceClusterBindingService datasourceClusterBindingService,
                                   AggregationSourceCapabilityProvider sourceCapabilityProvider,
                                   ObjectMapper objectMapper) {
        this.dataSourceService = dataSourceService;
        this.datasourceClusterBindingService = datasourceClusterBindingService;
        this.sourceCapabilityProvider = sourceCapabilityProvider;
        this.objectMapper = objectMapper;
    }

    List<TransferExecution> buildExecutions(FileTransferRunEntity run,
                                            Map<String, Object> snapshot) {
        List<Map<String, Object>> manualItems = maps(snapshot.get("manualItems"));
        if (!manualItems.isEmpty()) {
            return manualExecutions(run, snapshot, manualItems);
        }
        return List.of(taskExecution(run, snapshot));
    }

    List<TransferExecution> manualExecutions(FileTransferRunEntity run,
                                             Map<String, Object> snapshot,
                                             List<Map<String, Object>> items) {
        List<TransferExecution> executions = new ArrayList<TransferExecution>();
        for (Map<String, Object> item : items) {
            Long sourceClusterId = optionalLong(item.get("runtimeClusterId"))
                    .or(() -> optionalLong(item.get("sourceRuntimeClusterId"))).orElse(null);
            Long sourceDatasourceId = optionalLong(item.get("sourceDatasourceId")).orElse(null);
            Long targetClusterId = optionalLong(item.get("runtimeClusterId"))
                    .or(() -> optionalLong(item.get("targetRuntimeClusterId"))).orElse(null);
            Long targetDatasourceId = optionalLong(item.get("targetDatasourceId")).orElse(null);
            DataSourceDefinition source = requireDatasource(run, sourceClusterId, sourceDatasourceId);
            DataSourceDefinition target = requireDatasource(run, targetClusterId, targetDatasourceId);
            Map<String, Object> selection = new LinkedHashMap<String, Object>();
            selection.put("rootPath", String.valueOf(item.get("sourcePath")));
            selection.put("paths", List.of());
            selection.put("recursive", !Boolean.FALSE.equals(item.get("recursive")));
            selection.put("maxFiles", 100_000);
            Map<String, Object> mapping = new LinkedHashMap<String, Object>();
            String targetPath = String.valueOf(item.get("targetPath"));
            boolean directorySelection = Boolean.TRUE.equals(item.get("recursive"));
            mapping.put("targetRootPath", directorySelection
                    ? targetPath : TransferPaths.parent(targetPath));
            mapping.put("preserveRelativePath", directorySelection);
            if (!directorySelection) {
                mapping.put("targetPathTemplate", TransferPaths.fileName(targetPath));
            }
            Map<String, Object> spec = commonSpec(snapshot, source, sourceClusterId,
                    target, targetClusterId);
            spec.put("selection", selection);
            spec.put("mapping", mapping);
            executions.add(execution(sourceClusterId, sourceDatasourceId, targetClusterId,
                    targetDatasourceId, spec, source, target));
        }
        return executions;
    }

    private TransferExecution taskExecution(FileTransferRunEntity run,
                                             Map<String, Object> snapshot) {
        Long sourceClusterId = optionalLong(snapshot.get("runtimeClusterId"))
                .or(() -> optionalLong(snapshot.get("sourceRuntimeClusterId"))).orElse(null);
        Long sourceDatasourceId = optionalLong(snapshot.get("sourceDatasourceId")).orElse(null);
        Long targetClusterId = optionalLong(snapshot.get("runtimeClusterId"))
                .or(() -> optionalLong(snapshot.get("targetRuntimeClusterId"))).orElse(null);
        Long targetDatasourceId = optionalLong(snapshot.get("targetDatasourceId")).orElse(null);
        DataSourceDefinition source = requireDatasource(run, sourceClusterId, sourceDatasourceId);
        DataSourceDefinition target = requireDatasource(run, targetClusterId, targetDatasourceId);
        Map<String, Object> spec = commonSpec(snapshot, source, sourceClusterId,
                target, targetClusterId);
        spec.put("selection", map(snapshot.get("selection")));
        spec.put("mapping", map(snapshot.get("mapping")));
        return execution(sourceClusterId, sourceDatasourceId, targetClusterId,
                targetDatasourceId, spec, source, target);
    }

    private Map<String, Object> commonSpec(Map<String, Object> snapshot,
                                           DataSourceDefinition source, Long sourceClusterId,
                                           DataSourceDefinition target, Long targetClusterId) {
        Map<String, Object> spec = new LinkedHashMap<String, Object>();
        spec.put("schemaVersion", 1);
        spec.put("source", endpoint(source, sourceClusterId));
        spec.put("target", endpoint(target, targetClusterId));
        spec.put("policy", map(snapshot.get("policy")));
        spec.put("runtime", map(snapshot.get("runtime")));
        spec.put("timeZone", text(snapshot.get("timeZone"), "Asia/Shanghai"));
        spec.put("parameters", map(snapshot.get("parameters")));
        return spec;
    }

    private TransferExecution execution(Long sourceClusterId, Long sourceDatasourceId,
                                         Long targetClusterId, Long targetDatasourceId,
                                         Map<String, Object> spec,
                                         DataSourceDefinition source,
                                         DataSourceDefinition target) {
        if (!Objects.equals(sourceClusterId, targetClusterId)) {
            throw new IllegalStateException("FILE_TRANSFER_CROSS_CLUSTER_DISABLED");
        }
        TransferFileSystemFactory sourceFactory =
                () -> sourceCapabilityProvider.openTransferFileSystem(source);
        TransferFileSystemFactory targetFactory =
                () -> sourceCapabilityProvider.openTransferFileSystem(target);
        return new TransferExecution(sourceClusterId, sourceDatasourceId,
                targetClusterId, targetDatasourceId, "SOURCE_TO_TARGET", "LOCAL_WORKER",
                spec, sourceFactory, targetFactory);
    }

    private DataSourceDefinition requireDatasource(FileTransferRunEntity run,
                                                   Long clusterId,
                                                   Long datasourceId) {
        if (clusterId == null || datasourceId == null) {
            throw new IllegalArgumentException("File transfer endpoint identity is incomplete");
        }
        DataSourceDefinition datasource = dataSourceService.getInternalForProject(
                run.getProjectId(), datasourceId);
        if (datasource == null || !Boolean.TRUE.equals(datasource.getEnabled())
                || !Boolean.TRUE.equals(datasource.getExecutable())) {
            throw new IllegalStateException("File datasource is unavailable: " + datasourceId);
        }
        datasourceClusterBindingService.assertDatasourceApplicable(datasourceId, clusterId);
        return datasource;
    }

    private Map<String, Object> endpoint(DataSourceDefinition datasource, Long clusterId) {
        Map<String, Object> endpoint = new LinkedHashMap<String, Object>();
        endpoint.put("plugin", datasource.getTypeCode());
        endpoint.put("identity", clusterId + ":" + datasource.getId());
        endpoint.put("config", new LinkedHashMap<String, Object>());
        return endpoint;
    }

    private Optional<Long> optionalLong(Object value) {
        if (value instanceof Number number) {
            return Optional.of(number.longValue());
        }
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Long.valueOf(String.valueOf(value).trim()));
    }

    private String text(Object value, String fallback) {
        return value == null || String.valueOf(value).trim().isEmpty()
                ? fallback : String.valueOf(value).trim();
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            return new LinkedHashMap<String, Object>();
        }
        return objectMapper.convertValue(value,
                new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?>)) {
            return new ArrayList<Map<String, Object>>();
        }
        return objectMapper.convertValue(value,
                new TypeReference<ArrayList<Map<String, Object>>>() { });
    }

    static final class TransferExecution {
        final Long sourceClusterId;
        final Long sourceDatasourceId;
        final Long targetClusterId;
        final Long targetDatasourceId;
        final String direction;
        final String channel;
        final Map<String, Object> spec;
        final TransferFileSystemFactory sourceFactory;
        final TransferFileSystemFactory targetFactory;

        TransferExecution(Long sourceClusterId, Long sourceDatasourceId,
                          Long targetClusterId, Long targetDatasourceId,
                          String direction, String channel,
                          Map<String, Object> spec,
                          TransferFileSystemFactory sourceFactory,
                          TransferFileSystemFactory targetFactory) {
            this.sourceClusterId = sourceClusterId;
            this.sourceDatasourceId = sourceDatasourceId;
            this.targetClusterId = targetClusterId;
            this.targetDatasourceId = targetDatasourceId;
            this.direction = direction;
            this.channel = channel;
            this.spec = spec;
            this.sourceFactory = sourceFactory;
            this.targetFactory = targetFactory;
        }

        Map<String, Object> spec() {
            return spec;
        }
    }
}
