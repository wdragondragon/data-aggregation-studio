package com.jdragon.studio.infra.service;

import com.jdragon.aggregation.pluginloader.runtime.PluginRuntimeSession;
import com.jdragon.aggregation.datasource.file.transfer.StorageCapabilities;
import com.jdragon.aggregation.datasource.file.transfer.TransferFileEntry;
import com.jdragon.aggregation.datasource.file.transfer.TransferFilePage;
import com.jdragon.aggregation.datasource.file.transfer.TransferFileSystem;
import com.jdragon.aggregation.datasource.file.transfer.TransferWriteSession;
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
import com.jdragon.studio.dto.enums.ModelKind;
import com.jdragon.studio.commons.logging.StudioSensitiveLogSanitizer;
import com.jdragon.studio.infra.service.execution.AggregationSourceCapabilityProvider;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FilterOutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Executes datasource capabilities in the process that can actually reach the datasource. */
@Slf4j
public class RuntimeDatasourceProbeExecutor {
    private static final int MAX_ERROR_MESSAGE_LENGTH = 2 * 1024;
    private static final String UNKNOWN_ERROR_MESSAGE = "Unknown error";

    private final AggregationSourceCapabilityProvider provider;
    private final DataDevelopmentSqlExecutor sqlExecutor;

    public RuntimeDatasourceProbeExecutor(AggregationSourceCapabilityProvider provider,
                                          DataDevelopmentSqlExecutor sqlExecutor) {
        this.provider = provider;
        this.sqlExecutor = sqlExecutor;
    }

    public ConnectionTestResult test(DataSourceDefinition datasource) {
        long started = System.nanoTime();
        log.info("[DATASOURCE_PROBE_START] operationId={} datasourceId={} datasourceType={}",
                operationId(), datasourceId(datasource), datasourceType(datasource));
        try {
            ConnectionTestResult result = provider.testConnection(datasource);
            if (result == null) {
                result = new ConnectionTestResult();
                result.setSuccess(false);
                result.setMessage("Connection test returned no result");
            }
            result.setStatus(result.isSuccess() ? DataSourceConnectionStatus.AVAILABLE : DataSourceConnectionStatus.UNAVAILABLE);
            result.setDurationMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            if (result.isSuccess()) {
                log.info("[DATASOURCE_PROBE_COMPLETED] operationId={} datasourceId={} "
                                + "datasourceType={} success=true durationMillis={}",
                        operationId(), datasourceId(datasource), datasourceType(datasource),
                        result.getDurationMs());
            } else {
                log.warn("[DATASOURCE_PROBE_COMPLETED] operationId={} datasourceId={} "
                                + "datasourceType={} success=false status={} message={} durationMillis={}",
                        operationId(), datasourceId(datasource), datasourceType(datasource),
                        result.getStatus(), safeExceptionMessage(new IllegalStateException(result.getMessage())),
                        result.getDurationMs());
            }
            return result;
        } catch (RuntimeException exception) {
            log.error("[DATASOURCE_PROBE_FAILED] operationId={} datasourceId={} datasourceType={} "
                            + "exceptionType={} message={} durationMillis={}",
                    operationId(), datasourceId(datasource), datasourceType(datasource),
                    exception.getClass().getName(), safeExceptionMessage(exception),
                    elapsedMillis(started), exception);
            throw exception;
        }
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
        long startedAt = System.nanoTime();
        log.debug("[UF_BROWSE_START] 开始浏览非结构化目录 operationId={} datasourceId={} "
                        + "datasourceType={} path={} pageSize={} cursorPresent={}",
                operationId(), datasourceId(datasource), datasourceType(datasource), resolvedPath,
                resolvedPageSize, cursor != null && !cursor.isBlank());
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
            log.debug("[UF_BROWSE_COMPLETED] 非结构化目录浏览完成 operationId={} datasourceId={} "
                            + "datasourceType={} path={} entries={} hasMore={} durationMillis={}",
                    operationId(), datasourceId(datasource), datasourceType(datasource), resolvedPath,
                    view.getEntries().size(), view.getHasMore(), elapsedMillis(startedAt));
            return view;
        } catch (Exception exception) {
            logWorkerException("BROWSE", datasource, resolvedPath, null, exception, startedAt);
            throw failure("File browser failed", exception);
        }
    }

    public FileTransferFileEntryView stat(DataSourceDefinition datasource, String path) {
        String resolvedPath = normalizePath(path);
        long startedAt = System.nanoTime();
        log.debug("[UF_STAT_START] 开始读取非结构化文件属性 operationId={} datasourceId={} "
                        + "datasourceType={} path={}",
                operationId(), datasourceId(datasource), datasourceType(datasource), resolvedPath);
        try (TransferFileSystem fileSystem = provider.openTransferFileSystem(datasource)) {
            FileTransferFileEntryView entry = toFileEntry(fileSystem.stat(resolvedPath));
            log.debug("[UF_STAT_COMPLETED] 非结构化文件属性读取完成 operationId={} datasourceId={} "
                            + "datasourceType={} path={} directory={} size={} durationMillis={}",
                    operationId(), datasourceId(datasource), datasourceType(datasource), resolvedPath,
                    entry.getDirectory(), entry.getSize(), elapsedMillis(startedAt));
            return entry;
        } catch (Exception exception) {
            logWorkerException("STAT", datasource, resolvedPath, null, exception, startedAt);
            throw failure("File stat failed", exception);
        }
    }

    public void operate(DataSourceDefinition datasource, String operation, String sourcePath,
                        String targetPath, Boolean recursiveConfirmed) {
        String resolvedSource = normalizePath(sourcePath);
        String resolvedTarget = targetPath == null || targetPath.trim().isEmpty()
                ? null : normalizePath(targetPath);
        String resolvedOperation = operation == null ? "" : operation.trim().toUpperCase();
        long startedAt = System.nanoTime();
        log.info("[UF_OPERATION_START] 非结构化文件操作开始 operationId={} datasourceId={} "
                        + "datasourceType={} operation={} sourcePath={} targetPath={} recursiveConfirmed={}",
                operationId(), datasourceId(datasource), datasourceType(datasource), resolvedOperation,
                resolvedSource, resolvedTarget, Boolean.TRUE.equals(recursiveConfirmed));
        try (TransferFileSystem fileSystem = provider.openTransferFileSystem(datasource)) {
            switch (resolvedOperation) {
                case "CREATE_DIRECTORY":
                    if (resolvedTarget != null) {
                        throw new IllegalArgumentException("Target path is not used when creating a directory");
                    }
                    ensureMissing(fileSystem, resolvedSource);
                    fileSystem.mkdir(resolvedSource);
                    logOperationCompleted(datasource, resolvedOperation, resolvedSource,
                            resolvedTarget, startedAt);
                    return;
                case "RENAME":
                    if (resolvedTarget == null) {
                        throw new IllegalArgumentException("Target path is required");
                    }
                    ensureSameParent(resolvedSource, resolvedTarget);
                    ensureMissing(fileSystem, resolvedTarget);
                    movePath(fileSystem, fileSystem.stat(resolvedSource),
                            resolvedSource, resolvedTarget);
                    logOperationCompleted(datasource, resolvedOperation, resolvedSource,
                            resolvedTarget, startedAt);
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
                    logOperationCompleted(datasource, resolvedOperation, resolvedSource,
                            resolvedTarget, startedAt);
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
                    logOperationCompleted(datasource, resolvedOperation, resolvedSource,
                            resolvedTarget, startedAt);
                    return;
                default:
                    throw new IllegalArgumentException("Unsupported file operation: " + operation);
            }
        } catch (Exception exception) {
            logWorkerException(resolvedOperation, datasource, resolvedSource,
                    resolvedTarget, exception, startedAt);
            throw failure("File operation failed", exception);
        }
    }

    public void download(DataSourceDefinition datasource, String path, OutputStream output) {
        String resolvedPath = normalizePath(path);
        long startedAt = System.nanoTime();
        log.info("[UF_DOWNLOAD_START] 非结构化文件下载开始 operationId={} datasourceId={} "
                        + "datasourceType={} path={} selectionCount=1",
                operationId(), datasourceId(datasource), datasourceType(datasource), resolvedPath);
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
            log.info("[UF_DOWNLOAD_COMPLETED] 非结构化文件下载完成 operationId={} datasourceId={} "
                            + "datasourceType={} path={} outputBytes={} durationMillis={}",
                    operationId(), datasourceId(datasource), datasourceType(datasource), resolvedPath,
                    transferred, elapsedMillis(startedAt));
        } catch (Exception exception) {
            logWorkerException("DOWNLOAD", datasource, resolvedPath, null, exception, startedAt);
            throw failure("File download failed", exception);
        }
    }

    public long upload(DataSourceDefinition datasource, String targetPath, boolean overwrite,
                       long contentLength, InputStream input) {
        String resolvedTarget = normalizePath(targetPath);
        if ("/".equals(resolvedTarget)) {
            throw new IllegalArgumentException("A file cannot replace the root directory");
        }
        if (contentLength < 0L || input == null) {
            throw new IllegalArgumentException("Upload content length and stream are required");
        }
        String sessionId = UUID.randomUUID().toString();
        String temporaryPath = temporaryUploadPath(resolvedTarget, sessionId);
        TransferWriteSession session = null;
        long startedAt = System.nanoTime();
        log.info("[UF_UPLOAD_START] 非结构化文件上传开始 operationId={} datasourceId={} "
                        + "datasourceType={} targetPath={} overwrite={} declaredBytes={}",
                operationId(), datasourceId(datasource), datasourceType(datasource), resolvedTarget,
                overwrite, contentLength);
        try (TransferFileSystem fileSystem = provider.openTransferFileSystem(datasource)) {
            if (fileSystem.transferExists(resolvedTarget)) {
                TransferFileEntry existing = fileSystem.stat(resolvedTarget);
                if (existing.directory()) {
                    throw new FileAlreadyExistsException(
                            "Upload target is an existing directory: " + resolvedTarget);
                }
                if (!overwrite) {
                    throw new FileAlreadyExistsException(resolvedTarget);
                }
            }
            session = fileSystem.prepareWrite(temporaryPath, sessionId);
            if (session.confirmedOffset() != 0L) {
                throw new IOException("Upload temporary file was not empty");
            }
            long written = fileSystem.append(session, 0L, input, contentLength);
            if (written != contentLength) {
                throw new IOException("Unexpected end of upload for " + resolvedTarget
                        + ": expected " + contentLength + " bytes but received " + written);
            }
            fileSystem.commit(session, resolvedTarget, overwrite);
            log.info("[UF_UPLOAD_COMPLETED] 非结构化文件上传完成 operationId={} datasourceId={} "
                            + "datasourceType={} targetPath={} overwrite={} declaredBytes={} "
                            + "actualBytes={} durationMillis={}",
                    operationId(), datasourceId(datasource), datasourceType(datasource), resolvedTarget,
                    overwrite, contentLength, written, elapsedMillis(startedAt));
            return written;
        } catch (Exception exception) {
            if (session != null) {
                try (TransferFileSystem cleanup = provider.openTransferFileSystem(datasource)) {
                    cleanup.abort(session);
                } catch (Exception cleanupException) {
                    log.warn("[UF_ABORT_FAILED] 上传失败后清理临时目标失败 operationId={} datasourceId={} "
                                    + "datasourceType={} targetPath={} exceptionType={} message={}",
                            operationId(), datasourceId(datasource), datasourceType(datasource),
                            resolvedTarget, cleanupException.getClass().getName(),
                            safeExceptionMessage(cleanupException));
                    exception.addSuppressed(cleanupException);
                }
            }
            logWorkerException("UPLOAD", datasource, null, resolvedTarget, exception, startedAt);
            throw failure("File upload failed", exception);
        }
    }

    public void downloadArchive(DataSourceDefinition datasource, List<String> paths,
                                OutputStream output) {
        List<String> selectedPaths = normalizeArchivePaths(paths);
        long startedAt = System.nanoTime();
        CountingOutputStream countedOutput = new CountingOutputStream(output);
        log.info("[UF_DOWNLOAD_START] 非结构化归档下载开始 operationId={} datasourceId={} "
                        + "datasourceType={} selectionCount={}",
                operationId(), datasourceId(datasource), datasourceType(datasource), selectedPaths.size());
        try (TransferFileSystem fileSystem = provider.openTransferFileSystem(datasource)) {
            List<ArchiveSelection> selections = new ArrayList<ArchiveSelection>();
            for (String path : selectedPaths) {
                selections.add(new ArchiveSelection(path, fileSystem.stat(path)));
            }
            selections.sort(Comparator.comparingInt(selection -> selection.path().length()));
            List<ArchiveSelection> effectiveSelections = new ArrayList<ArchiveSelection>();
            for (ArchiveSelection selection : selections) {
                boolean covered = effectiveSelections.stream().anyMatch(parent ->
                        parent.entry().directory()
                                && ("/".equals(parent.path())
                                || selection.path().startsWith(parent.path() + "/")));
                if (!covered) {
                    effectiveSelections.add(selection);
                }
            }
            String basePath = commonArchiveBase(effectiveSelections.stream()
                    .map(ArchiveSelection::path).toList());
            Set<String> zipNames = new HashSet<String>();
            ZipOutputStream archive = new ZipOutputStream(countedOutput, java.nio.charset.StandardCharsets.UTF_8);
            for (ArchiveSelection selection : effectiveSelections) {
                writeArchiveEntry(fileSystem, selection.entry(), selection.path(),
                        basePath, archive, zipNames);
            }
            archive.finish();
            archive.flush();
            log.info("[UF_DOWNLOAD_COMPLETED] 非结构化归档下载完成 operationId={} datasourceId={} "
                            + "datasourceType={} selectionCount={} outputBytes={} durationMillis={}",
                    operationId(), datasourceId(datasource), datasourceType(datasource), selectedPaths.size(),
                    countedOutput.count(), elapsedMillis(startedAt));
        } catch (Exception exception) {
            logWorkerException("DOWNLOAD_ARCHIVE", datasource, null, null, exception, startedAt);
            throw failure("File archive download failed", exception);
        }
    }

    private void logWorkerException(String operation, DataSourceDefinition datasource,
                                    String sourcePath, String targetPath,
                                    Exception exception, long startedAt) {
        if (expectedRejection(exception)) {
            log.warn("[UF_OPERATION_REJECTED] Worker 非结构化文件操作被拒绝 operationId={} "
                            + "datasourceId={} datasourceType={} operation={} sourcePath={} targetPath={} "
                            + "exceptionType={} message={} durationMillis={}",
                    operationId(), datasourceId(datasource), datasourceType(datasource), operation,
                    sourcePath, targetPath, exception.getClass().getName(),
                    safeExceptionMessage(exception),
                    elapsedMillis(startedAt));
            return;
        }
        logWorkerFailure(operation, datasource, sourcePath, targetPath, exception, startedAt);
    }

    private void logOperationCompleted(DataSourceDefinition datasource, String operation,
                                       String sourcePath, String targetPath, long startedAt) {
        log.info("[UF_OPERATION_COMPLETED] 非结构化文件操作完成 operationId={} datasourceId={} "
                        + "datasourceType={} operation={} sourcePath={} targetPath={} durationMillis={}",
                operationId(), datasourceId(datasource), datasourceType(datasource), operation,
                sourcePath, targetPath, elapsedMillis(startedAt));
    }

    private void logWorkerFailure(String operation, DataSourceDefinition datasource,
                                  String sourcePath, String targetPath,
                                  Exception exception, long startedAt) {
        log.error("[UF_WORKER_FAILED] Worker 非结构化文件操作失败 operationId={} datasourceId={} "
                        + "datasourceType={} operation={} sourcePath={} targetPath={} exceptionType={} "
                        + "message={} durationMillis={}",
                operationId(), datasourceId(datasource), datasourceType(datasource), operation,
                sourcePath, targetPath, exception.getClass().getName(),
                safeExceptionMessage(exception),
                elapsedMillis(startedAt), exception);
    }

    private boolean expectedRejection(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof IllegalArgumentException
                    || current instanceof java.nio.file.FileAlreadyExistsException
                    || current instanceof java.nio.file.NoSuchFileException
                    || current instanceof java.nio.file.AccessDeniedException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("permission denied")
                        || normalized.contains("access denied")
                        || message.contains("权限不足")
                        || message.contains("无权限")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private IllegalStateException failure(String prefix, Exception exception) {
        return new IllegalStateException(prefix + ": " + safeExceptionMessage(exception), exception);
    }

    private String safeExceptionMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        String sanitized = StudioSensitiveLogSanitizer.sanitizeSingleLine(
                message, MAX_ERROR_MESSAGE_LENGTH);
        return sanitized == null || sanitized.isBlank() ? UNKNOWN_ERROR_MESSAGE : sanitized;
    }

    private String operationId() {
        return MDC.get("operationId");
    }

    private Long datasourceId(DataSourceDefinition datasource) {
        return datasource == null ? null : datasource.getId();
    }

    private String datasourceType(DataSourceDefinition datasource) {
        return datasource == null ? null : datasource.getTypeCode();
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;

        private CountingOutputStream(OutputStream output) {
            super(output);
        }

        @Override
        public void write(int value) throws IOException {
            out.write(value);
            count++;
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException {
            out.write(values, offset, length);
            count += length;
        }

        private long count() {
            return count;
        }
    }

    private void writeArchiveEntry(TransferFileSystem fileSystem, TransferFileEntry entry,
                                   String path, String basePath, ZipOutputStream archive,
                                   Set<String> zipNames) throws IOException {
        String zipName = archiveEntryName(path, basePath);
        if (entry.directory()) {
            if (!zipName.isEmpty()) {
                putZipEntry(archive, zipNames, zipName + "/", entry.modifiedTimeMillis());
                archive.closeEntry();
            }
            String cursor = null;
            do {
                TransferFilePage page = fileSystem.listPage(path, cursor, 500);
                for (TransferFileEntry child : page.entries()) {
                    String childPath = normalizePath(child.path());
                    if (!("/".equals(path) ? childPath.startsWith("/")
                            : childPath.startsWith(path + "/"))) {
                        throw new IOException("Directory listing returned a path outside " + path);
                    }
                    writeArchiveEntry(fileSystem, child, childPath, basePath, archive, zipNames);
                }
                cursor = page.nextCursor();
            } while (cursor != null && !cursor.isBlank());
            return;
        }
        putZipEntry(archive, zipNames, zipName, entry.modifiedTimeMillis());
        long transferred = 0L;
        try (InputStream input = fileSystem.openRead(path, 0L, entry.size())) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    archive.write(buffer, 0, read);
                    transferred += read;
                }
            }
        }
        archive.closeEntry();
        if (transferred != entry.size()) {
            throw new IOException("Unexpected end of file for " + path
                    + ": expected " + entry.size() + " bytes but received " + transferred);
        }
    }

    private void putZipEntry(ZipOutputStream archive, Set<String> zipNames,
                             String name, long modifiedTimeMillis) throws IOException {
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("../")
                || normalized.equals("..") || normalized.indexOf('\0') >= 0) {
            throw new IOException("Unsafe ZIP entry name: " + name);
        }
        if (!zipNames.add(normalized)) {
            throw new IOException("Duplicate ZIP entry name: " + normalized);
        }
        ZipEntry zipEntry = new ZipEntry(normalized);
        if (modifiedTimeMillis > 0L) {
            zipEntry.setTime(modifiedTimeMillis);
        }
        archive.putNextEntry(zipEntry);
    }

    private List<String> normalizeArchivePaths(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            throw new IllegalArgumentException("At least one archive path is required");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        for (String path : paths) {
            unique.add(normalizePath(path));
        }
        return new ArrayList<String>(unique);
    }

    private String commonArchiveBase(List<String> paths) {
        String common = parentPath(paths.get(0));
        for (int index = 1; index < paths.size(); index++) {
            String candidate = parentPath(paths.get(index));
            while (!"/".equals(common)
                    && !candidate.equals(common)
                    && !candidate.startsWith(common + "/")) {
                common = parentPath(common);
            }
        }
        return common;
    }

    private String archiveEntryName(String path, String basePath) throws IOException {
        if ("/".equals(path)) {
            return "";
        }
        String relative = "/".equals(basePath) ? path.substring(1)
                : path.substring(basePath.length() + 1);
        if (relative.isBlank()) {
            throw new IOException("The archive root cannot be empty");
        }
        return relative;
    }

    private String temporaryUploadPath(String targetPath, String sessionId) {
        String parent = parentPath(targetPath);
        String name = targetPath.substring(targetPath.lastIndexOf('/') + 1);
        return ("/".equals(parent) ? "" : parent) + "/." + name
                + ".studio-upload-" + sessionId + ".part";
    }

    private String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    private record ArchiveSelection(String path, TransferFileEntry entry) {
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
                // Queue locators are topics/queues rather than generic datasets. Keep the
                // discovered model kind when selective sync bypasses full discovery.
                if (isQueueDatasource(datasource)) {
                    candidate.setModelKind(ModelKind.TOPIC);
                }
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

    private boolean isQueueDatasource(DataSourceDefinition datasource) {
        if (datasource == null || datasource.getTypeCode() == null) {
            return false;
        }
        String typeCode = datasource.getTypeCode().trim();
        return "kafka".equalsIgnoreCase(typeCode)
                || "rocketmq".equalsIgnoreCase(typeCode)
                || "rabbitmq".equalsIgnoreCase(typeCode);
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
