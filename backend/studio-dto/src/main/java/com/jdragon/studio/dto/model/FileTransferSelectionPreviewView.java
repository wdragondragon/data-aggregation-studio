package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class FileTransferSelectionPreviewView {
    private Boolean resolvedAtRuntime = Boolean.TRUE;
    private String previewId;
    private Long plannedAtMillis;
    private Long totalFiles;
    private Long totalBytes;
    private Integer sampleCount;
    private Boolean hasMore;
    private Map<String, Object> resolvedSelection = new LinkedHashMap<String, Object>();
    private Map<String, Object> resolvedMapping = new LinkedHashMap<String, Object>();
    private List<FileTransferPreviewItemView> sample = new ArrayList<FileTransferPreviewItemView>();
}
