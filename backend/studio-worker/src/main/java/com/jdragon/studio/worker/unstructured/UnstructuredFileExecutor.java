package com.jdragon.studio.worker.unstructured;

import com.jdragon.studio.dto.model.DataSourceDefinition;
import com.jdragon.studio.dto.model.FileTransferBrowserPageView;
import com.jdragon.studio.dto.model.FileTransferFileEntryView;
import com.jdragon.studio.infra.service.RuntimeDatasourceProbeExecutor;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

@Component
public class UnstructuredFileExecutor {

    private final RuntimeDatasourceProbeExecutor delegate;

    public UnstructuredFileExecutor(RuntimeDatasourceProbeExecutor delegate) {
        this.delegate = delegate;
    }

    public FileTransferBrowserPageView browse(DataSourceDefinition datasource,
                                              String path,
                                              String cursor,
                                              Integer pageSize) {
        return delegate.browse(datasource, path, cursor, pageSize);
    }

    public FileTransferFileEntryView stat(DataSourceDefinition datasource, String path) {
        return delegate.stat(datasource, path);
    }

    public void operate(DataSourceDefinition datasource,
                        String operation,
                        String sourcePath,
                        String targetPath,
                        Boolean recursiveConfirmed) {
        delegate.operate(datasource, operation, sourcePath, targetPath, recursiveConfirmed);
    }

    public void download(DataSourceDefinition datasource, String path, OutputStream output) {
        delegate.download(datasource, path, output);
    }

    public long upload(DataSourceDefinition datasource,
                       String targetPath,
                       boolean overwrite,
                       long contentLength,
                       InputStream input) {
        return delegate.upload(datasource, targetPath, overwrite, contentLength, input);
    }

    public void downloadArchive(DataSourceDefinition datasource,
                                List<String> paths,
                                OutputStream output) {
        delegate.downloadArchive(datasource, paths, output);
    }
}
