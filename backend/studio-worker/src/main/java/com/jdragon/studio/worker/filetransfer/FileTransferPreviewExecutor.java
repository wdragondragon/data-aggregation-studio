package com.jdragon.studio.worker.filetransfer;

import com.jdragon.aggregation.datasource.file.transfer.TransferFileSystem;
import com.jdragon.aggregation.transfer.PreparedTransfer;
import com.jdragon.aggregation.transfer.model.TransferMapping;
import com.jdragon.aggregation.transfer.model.TransferPlanItem;
import com.jdragon.aggregation.transfer.model.TransferSelection;
import com.jdragon.aggregation.transfer.model.TransferSpec;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.FileTransferPreviewItemView;
import com.jdragon.studio.dto.model.FileTransferSelectionPreviewView;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import com.jdragon.studio.infra.service.UnstructuredManagementService;
import com.jdragon.studio.dto.enums.UnstructuredAclPermission;
import com.jdragon.studio.infra.service.StudioFileTransferContractAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class FileTransferPreviewExecutor {

    private final AggregationSourceCapabilityProvider sourceCapabilityProvider;
    private final StudioFileTransferContractAdapter contractAdapter;
    private final FileTransferEnginePort enginePort;
    private final UnstructuredManagementService unstructuredManagementService;

    public FileTransferPreviewExecutor(AggregationSourceCapabilityProvider sourceCapabilityProvider) {
        this(sourceCapabilityProvider, new StudioFileTransferContractAdapter(),
                new DataAggregationFileTransferEngineAdapter(), null);
    }

    public FileTransferPreviewExecutor(AggregationSourceCapabilityProvider sourceCapabilityProvider,
                                       StudioFileTransferContractAdapter contractAdapter,
                                       FileTransferEnginePort enginePort) {
        this(sourceCapabilityProvider, contractAdapter, enginePort, null);
    }

    @Autowired
    public FileTransferPreviewExecutor(AggregationSourceCapabilityProvider sourceCapabilityProvider,
                                       StudioFileTransferContractAdapter contractAdapter,
                                       FileTransferEnginePort enginePort,
                                       UnstructuredManagementService unstructuredManagementService) {
        this.sourceCapabilityProvider = sourceCapabilityProvider;
        this.contractAdapter = contractAdapter;
        this.enginePort = enginePort;
        this.unstructuredManagementService = unstructuredManagementService;
    }

    public FileTransferSelectionPreviewView preview(DataSourceDefinition datasource,
                                                    Map<String, Object> rawSpec,
                                                    Map<String, String> parameters,
                                                    Integer limit) {
        return preview(datasource, rawSpec, parameters, limit, null);
    }

    public FileTransferSelectionPreviewView preview(DataSourceDefinition datasource,
                                                    Map<String, Object> rawSpec,
                                                    Map<String, String> parameters,
                                                    Integer limit,
                                                    Long runtimeClusterId) {
        if (datasource == null || datasource.getId() == null || datasource.getTypeCode() == null) {
            throw new IllegalArgumentException("File transfer preview datasource is required");
        }
        int sampleLimit = limit == null ? 200 : Math.max(1, Math.min(1000, limit));
        Map<String, Object> spec = rawSpec == null
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(rawSpec);
        spec.put("schemaVersion", 1);
        spec.put("source", endpoint(datasource.getTypeCode(), "preview-source:" + datasource.getId()));
        spec.putIfAbsent("target", endpoint("preview-target", "preview-target"));
        spec.put("parameters", parameters == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(parameters));
        String previewId = "preview-" + UUID.randomUUID();
        Instant plannedAt = Instant.now();
        TransferSpec transferSpec = contractAdapter.map(spec);
        TransferSpec resolvedSpec = new com.jdragon.aggregation.transfer.DynamicTemplateResolver()
                .resolve(transferSpec, previewId, plannedAt);
        if (resolvedSpec.selection().rootPath() == null || resolvedSpec.selection().rootPath().isBlank()) {
            throw new IllegalArgumentException("Resolved selection.rootPath is empty");
        }
        if (resolvedSpec.mapping().targetRootPath() == null || resolvedSpec.mapping().targetRootPath().isBlank()) {
            throw new IllegalArgumentException("Resolved mapping.targetRootPath is empty");
        }
        String resolvedRegex = resolvedSpec.selection().includeRegex();
        if (resolvedRegex != null && !resolvedRegex.isBlank()) {
            try {
                Pattern.compile(resolvedRegex);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(
                        "Invalid resolved regular expression in selection.includeRegex: " + resolvedRegex,
                        exception);
            }
        }
        if (unstructuredManagementService != null && runtimeClusterId != null) {
            unstructuredManagementService.assertPermission(runtimeClusterId, datasource.getId(),
                    resolvedSpec.selection().rootPath(), UnstructuredAclPermission.DOWNLOAD);
        }
        try (TransferFileSystem source = sourceCapabilityProvider.openTransferFileSystem(datasource)) {
            PreparedTransfer prepared = enginePort.prepare(
                    resolvedSpec, source, null, previewId, plannedAt, null);
            return toView(prepared, sampleLimit);
        } catch (Exception exception) {
            throw new IllegalStateException("File transfer preview failed: " + exception.getMessage(), exception);
        }
    }

    private FileTransferSelectionPreviewView toView(PreparedTransfer prepared, int sampleLimit) {
        FileTransferSelectionPreviewView view = new FileTransferSelectionPreviewView();
        view.setPreviewId(prepared.plan().runId());
        view.setPlannedAtMillis(prepared.plan().plannedAt().toEpochMilli());
        long totalBytes = 0L;
        List<TransferPlanItem> items = prepared.plan().items();
        for (TransferPlanItem item : items) {
            totalBytes += item.sourceSnapshot().size();
        }
        view.setTotalFiles((long) items.size());
        view.setTotalBytes(totalBytes);
        int sampleCount = Math.min(sampleLimit, items.size());
        view.setSampleCount(sampleCount);
        view.setHasMore(items.size() > sampleCount);
        for (int index = 0; index < sampleCount; index++) {
            TransferPlanItem item = items.get(index);
            FileTransferPreviewItemView sample = new FileTransferPreviewItemView();
            sample.setSourcePath(item.sourcePath());
            sample.setTargetPath(item.targetPath());
            sample.setRelativePath(item.relativePath());
            sample.setSize(item.sourceSnapshot().size());
            sample.setModifiedAtMillis(item.sourceSnapshot().modifiedTimeMillis());
            sample.setEtag(item.sourceSnapshot().etag());
            view.getSample().add(sample);
        }
        view.setResolvedSelection(selection(prepared.resolvedSpec().selection()));
        view.setResolvedMapping(mapping(prepared.resolvedSpec().mapping()));
        return view;
    }

    private Map<String, Object> endpoint(String plugin, String identity) {
        Map<String, Object> endpoint = new LinkedHashMap<String, Object>();
        endpoint.put("plugin", plugin);
        endpoint.put("identity", identity);
        endpoint.put("config", new LinkedHashMap<String, Object>());
        return endpoint;
    }

    private Map<String, Object> selection(TransferSelection value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("rootPath", value.rootPath());
        result.put("paths", new ArrayList<String>(value.paths()));
        result.put("recursive", value.recursive());
        result.put("includeGlobs", new ArrayList<String>(value.includeGlobs()));
        result.put("includeRegex", value.includeRegex());
        result.put("excludeGlobs", new ArrayList<String>(value.excludeGlobs()));
        result.put("minSize", value.minSize());
        result.put("maxSize", value.maxSize());
        result.put("modifiedAfterMillis", value.modifiedAfterMillis());
        result.put("modifiedBeforeMillis", value.modifiedBeforeMillis());
        result.put("maxFiles", value.maxFiles());
        return result;
    }

    private Map<String, Object> mapping(TransferMapping value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("targetRootPath", value.targetRootPath());
        result.put("preserveRelativePath", value.preserveRelativePath());
        result.put("targetPathTemplate", value.targetPathTemplate());
        return result;
    }
}
