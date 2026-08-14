package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class UnstructuredUploadResultView {
    private String operation;
    private String targetPath;
    private Long bytes;
    private Boolean overwritten;
    private String message;
}
