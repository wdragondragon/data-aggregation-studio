package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class UnstructuredOperationResultView {
    private String operation;
    private String sourcePath;
    private String targetPath;
    private boolean recursive;
    private String message;
}
