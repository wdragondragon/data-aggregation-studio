package com.jdragon.studio.infra.service;

import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeSession;
import com.jdragon.aggregation.datasource.file.transfer.StorageCapabilities;
import com.jdragon.aggregation.datasource.file.transfer.TransferFileEntry;
import com.jdragon.aggregation.datasource.file.transfer.TransferFilePage;
import com.jdragon.aggregation.datasource.file.transfer.TransferFileSystem;
import com.jdragon.studio.dto.enums.DataSourceConnectionStatus;
import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.FileTransferBrowserPageView;
import com.jdragon.studio.dto.model.FileTransferFileEntryView;
import com.jdragon.studio.dto.model.DataModelDefinition;
import com.jdragon.studio.dto.model.RuntimeDatasourceHydrationItemView;
import com.jdragon.studio.dto.model.RuntimeDatasourceHydrationResultView;
import com.jdragon.studio.dto.model.SqlExecutionResultView;
import com.jdragon.studio.dto.model.dto.ConnectionTestResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryOptionResult;
import com.jdragon.studio.dto.model.dto.ModelDiscoveryResult;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Executes datasource capabilities in the process that can actually reach the datasource. */
public class RuntimeDatasourceProbeExecutor {
    private final AggregationSourceCapabilityProvider provider;
    private final DataDevelopmentSqlExecutor sqlExecutor;

    public RuntimeDatasourceProbeExecutor(AggregationSourceCapabilityProvider provider,
                                          DataDevelopmentSqlExecutor sqlExecutor) {
        this.provider = provider;
        this.sqlExecutor = sqlExecutor;
    }

    public ConnectionTestResult test(DataSourceDefinition datasource) {
        long started = System.nanoTime();
        ConnectionTestResult result = provider.testConnection(datasource);
        if (result == null) { result = new ConnectionTestResult(); result.setSuccess(false); result.setMessage("Connection test returned no result"); }
        result.setStatus(result.isSuccess() ? DataSourceConnectionStatus.AVAILABLE : DataSourceConnectionStatus.UNAVAILABLE);
        result.setDurationMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return result;
    }
    public ModelDiscoveryResult discover(DataSourceDefinition datasource, String keyword, Integer pageNo, Integer pageSize) {
        return provider.discoverModels(datasource, keyword, pageNo, pageSize);
    }
    public ModelDiscoveryOptionResult discoverOptions(DataSourceDefinition datasource, String keyword, Integer pageNo, Integer pageSize) {
        return provider.discoverModelOptions(datasource, keyword, pageNo, pageSize);
    }

    public FileTransferBrowserPageView browse(DataSourceDefinition datasource, String path,
                                               String cursor, Integer pageSize) {
        int resolvedPageSize = pageSize == null ? 200 : Math.max(1, Math.min(1000, pageSize));
        String resolvedPath = path == null || path.trim().isEmpty() ? "/" : path.trim();
        try (TransferFileSystem fileSystem = provider.openTransferFileSystem(datasource)) {
            TransferFilePage page = fileSystem.listPage(resolvedPath, cursor, resolvedPageSize);
            FileTransferBrowserPageView view = new FileTransferBrowserPageView();
            view.setPath(resolvedPath);
            view.setInitialPath(fileSystem.initialPath());
            view.setNextCursor(page.nextCursor());
            view.setPageSize(resolvedPageSize);
            view.setHasMore(page.truncated());
            for (TransferFileEntry entry : page.entries()) {
                FileTransferFileEntryView item = new FileTransferFileEntryView();
                item.setPath(entry.path());
                item.setName(entry.name());
                item.setDirectory(entry.directory());
                item.setSize(entry.size());
                item.setModifiedAtMillis(entry.modifiedTimeMillis());
                item.setEtag(entry.etag());
                view.getEntries().add(item);
            }
            StorageCapabilities capabilities = fileSystem.capabilities();
            Map<String, Object> values = new LinkedHashMap<String, Object>();
            values.put("rangeRead", capabilities.rangeRead());
            values.put("resumableWrite", capabilities.resumableWrite());
            values.put("atomicMove", capabilities.atomicMove());
            values.put("serverSideCopy", capabilities.serverSideCopy());
            values.put("nativePaging", capabilities.nativePaging());
            values.put("multipartWrite", capabilities.multipartWrite());
            values.put("checksumAlgorithms", capabilities.checksumAlgorithms());
            view.setCapabilities(values);
            return view;
        } catch (Exception exception) {
            throw new IllegalStateException("File browser failed: " + exception.getMessage(), exception);
        }
    }

    public FileTransferFileEntryView stat(DataSourceDefinition datasource, String path) {
        String resolvedPath = normalizePath(path);
        try (TransferFileSystem fileSystem = provider.openTransferFileSystem(datasource)) {
            return toFileEntry(fileSystem.stat(resolvedPath));
        } catch (Exception exception) {
            throw new IllegalStateException("File stat failed: " + exception.getMessage(), exception);
        }
    }

    public void operate(DataSourceDefinition datasource, String operation, String sourcePath,
                        String targetPath, Boolean recursiveConfirmed) {
        String resolvedSource = normalizePath(sourcePath);
        String resolvedTarget = targetPath == null || targetPath.trim().isEmpty()
                ? null : normalizePath(targetPath);
        try (TransferFileSystem fileSystem = provider.openTransferFileSystem(datasource)) {
            switch (operation == null ? "" : operation.trim().toUpperCase()) {
                case "CREATE_DIRECTORY":
                    if (resolvedTarget != null) {
                        throw new IllegalArgumentException("Target path is not used when creating a directory");
                    }
                    ensureMissing(fileSystem, resolvedSource);
                    fileSystem.mkdir(resolvedSource);
                    return;
                case "RENAME":
                    if (resolvedTarget == null) {
                        throw new IllegalArgumentException("Target path is required");
                    }
                    ensureSameParent(resolvedSource, resolvedTarget);
                    ensureMissing(fileSystem, resolvedTarget);
                    movePath(fileSystem, fileSystem.stat(resolvedSource),
                            resolvedSource, resolvedTarget);
                    return;
                case "MOVE":
                    if (resolvedTarget == null) {
                        throw new IllegalArgumentException("Target path is required");
                    }
                    if (resolvedTarget.startsWith(resolvedSource + "/")) {
                        throw new IllegalArgumentException("A directory cannot be moved into itself");
                    }
                    ensureMissing(fileSystem, resolvedTarget);
                    movePath(fileSystem, fileSystem.stat(resolvedSource),
                            resolvedSource, resolvedTarget);
                    return;
                case "DELETE":
                    if ("/".equals(resolvedSource)) {
                        throw new IllegalArgumentException("The root directory cannot be deleted");
                    }
                    TransferFileEntry source = fileSystem.stat(resolvedSource);
                    if (source.directory() && !directoryEmpty(fileSystem, resolvedSource)
                            && !Boolean.TRUE.equals(recursiveConfirmed)) {
                        throw new IllegalArgumentException("Non-empty directory deletion requires recursive confirmation");
                    }
                    fileSystem.delete(resolvedSource);
                    return;
                default:
                    throw new IllegalArgumentException("Unsupported file operation: " + operation);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("File operation failed: " + exception.getMessage(), exception);
        }
    }

    public void download(DataSourceDefinition datasource, String path, OutputStream output) {
        String resolvedPath = normalizePath(path);
        try (TransferFileSystem fileSystem = provider.openTransferFileSystem(datasource)) {
            TransferFileEntry entry = fileSystem.stat(resolvedPath);
            if (entry.directory()) {
                throw new IllegalArgumentException("Only files can be downloaded");
            }
            long transferred = 0L;
            try (InputStream input = fileSystem.openRead(resolvedPath, 0L, entry.size())) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        output.write(buffer, 0, read);
                        transferred += read;
                    }
                }
            }
            if (transferred != entry.size()) {
                throw new IOException("Unexpected end of file for " + resolvedPath
                        + ": expected " + entry.size() + " bytes but received " + transferred);
            }
            output.flush();
        } catch (Exception exception) {
            throw new IllegalStateException("File download failed: " + exception.getMessage(), exception);
        }
    }

    private void movePath(TransferFileSystem fileSystem, TransferFileEntry source,
                          String sourcePath, String targetPath) throws IOException {
        if (!source.directory() || fileSystem.capabilities().atomicMove()) {
            fileSystem.move(sourcePath, targetPath, false);
            return;
        }
        fileSystem.mkdir(targetPath);
        String cursor = null;
        do {
            TransferFilePage page = fileSystem.listPage(sourcePath, cursor, 500);
            for (TransferFileEntry child : page.entries()) {
                String childPath = normalizePath(child.path());
                String relative = childPath.substring(sourcePath.length());
                if (relative.startsWith("/")) {
                    relative = relative.substring(1);
                }
                String destination = "/".equals(targetPath) ? "/" + relative
                        : targetPath + "/" + relative;
                movePath(fileSystem, child, childPath, normalizePath(destination));
            }
            cursor = page.nextCursor();
        } while (cursor != null && !cursor.isBlank());
        fileSystem.delete(sourcePath);
    }

    private boolean directoryEmpty(TransferFileSystem fileSystem, String path) throws IOException {
        // Object stores may return a directory marker as the first page item. A
        // one-item page can therefore look empty after the marker is filtered,
        // even though descendants remain on the next page. Treat a truncated
        // or otherwise non-empty page as non-empty so an unconfirmed delete
        // always fails closed.
        TransferFilePage page = fileSystem.listPage(path, null, 1_000);
        return !page.truncated() && page.entries().isEmpty();
    }

    private void ensureMissing(TransferFileSystem fileSystem, String path) throws IOException {
        if (fileSystem.transferExists(path)) {
            throw new FileAlreadyExistsException(path);
        }
    }

    private void ensureSameParent(String source, String target) {
        int sourceSlash = source.lastIndexOf('/');
        int targetSlash = target.lastIndexOf('/');
        String sourceParent = sourceSlash <= 0 ? "/" : source.substring(0, sourceSlash);
        String targetParent = targetSlash <= 0 ? "/" : target.substring(0, targetSlash);
        if (!sourceParent.equals(targetParent)) {
            throw new IllegalArgumentException("Rename must stay in the same parent directory");
        }
    }

    private String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return "/";
        }
        String value = rawPath.trim().replace('\\', '/');
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Path contains a NUL character");
        }
        while (value.contains("//")) {
            value = value.replace("//", "/");
        }
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        List<String> segments = new ArrayList<String>();
        for (String segment : value.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("Path must not contain '..'");
            }
            segments.add(segment);
        }
        return segments.isEmpty() ? "/" : "/" + String.join("/", segments);
    }

    private FileTransferFileEntryView toFileEntry(TransferFileEntry entry) {
        FileTransferFileEntryView item = new FileTransferFileEntryView();
        item.setPath(entry.path());
        item.setName(entry.name());
        item.setDirectory(entry.directory());
        item.setSize(entry.size());
        item.setModifiedAtMillis(entry.modifiedTimeMillis());
        item.setEtag(entry.etag());
        return item;
    }

    public RuntimeDatasourceHydrationResultView hydrate(DataSourceDefinition datasource,
                                                         List<String> physicalLocators) {
        if (PluginRuntimeSession.current() != null) {
            return hydrateWithPluginRuntimeSession(datasource, physicalLocators);
        }
        try (PluginRuntimeSession operationSession = PluginRuntimeSession.open()) {
            return hydrateWithPluginRuntimeSession(datasource, physicalLocators);
        }
    }

    private RuntimeDatasourceHydrationResultView hydrateWithPluginRuntimeSession(
            DataSourceDefinition datasource,
            List<String> physicalLocators) {
        List<DataModelDefinition> candidates = new ArrayList<DataModelDefinition>();
        if (physicalLocators == null || physicalLocators.isEmpty()) {
            candidates.addAll(provider.discoverModels(datasource).getModels());
        } else {
            for (String locator : physicalLocators) {
                if (locator == null || locator.trim().isEmpty()) continue;
                DataModelDefinition candidate = new DataModelDefinition();
                candidate.setDatasourceId(datasource.getId());
                candidate.setName(locator.trim());
                candidate.setPhysicalLocator(locator.trim());
                Map<String, Object> metadata = new LinkedHashMap<String, Object>();
                metadata.put("sourceType", datasource.getTypeCode());
                metadata.put("discoveryMode", "AUTO");
                metadata.put("physicalName", locator.trim());
                candidate.setTechnicalMetadata(metadata);
                candidate.setBusinessMetadata(new LinkedHashMap<String, Object>());
                candidates.add(candidate);
            }
        }
        RuntimeDatasourceHydrationResultView result = new RuntimeDatasourceHydrationResultView();
        for (AggregationSourceCapabilityProvider.HydrationResult hydrated
                : provider.hydrateDiscoveredModels(datasource, candidates)) {
            RuntimeDatasourceHydrationItemView item = new RuntimeDatasourceHydrationItemView();
            item.setPhysicalLocator(hydrated.getPhysicalLocator());
            item.setSuccess(hydrated.isSuccess());
            item.setDefinition(hydrated.getDefinition());
            item.setMessage(hydrated.getErrorMessage());
            result.getItems().add(item);
        }
        return result;
    }

    public List<Map<String, Object>> preview(DataSourceDefinition datasource,
                                             DataModelDefinition model,
                                             Integer limit) {
        return provider.preview(datasource, model, limit == null ? 20 : Math.max(1, Math.min(limit, 1000)));
    }

    public SqlExecutionResultView query(DataSourceDefinition datasource,
                                        String sql,
                                        List<Object> parameters,
                                        Integer maxRows) {
        return sqlExecutor.executePreparedQuery(datasource, sql,
                parameters == null ? new ArrayList<Object>() : parameters,
                maxRows == null ? 1 : Math.max(1, Math.min(maxRows, 1000)));
    }
}
