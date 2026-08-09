package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class FileTransferFileEntryView {
    private String path;
    private String name;
    private Boolean directory;
    private Long size;
    private Long modifiedAtMillis;
    private String etag;
}
