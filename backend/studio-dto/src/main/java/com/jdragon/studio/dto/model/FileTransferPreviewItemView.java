package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class FileTransferPreviewItemView {
    private String sourcePath;
    private String targetPath;
    private String relativePath;
    private Long size;
    private Long modifiedAtMillis;
    private String etag;
}
