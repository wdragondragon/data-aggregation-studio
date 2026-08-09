package com.jdragon.studio.dto.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class FileTransferBrowserPageView {
    private String path;
    private String nextCursor;
    private Integer pageSize;
    private Boolean hasMore;
    private List<FileTransferFileEntryView> entries = new ArrayList<FileTransferFileEntryView>();
    private Map<String, Object> capabilities = new LinkedHashMap<String, Object>();
}
