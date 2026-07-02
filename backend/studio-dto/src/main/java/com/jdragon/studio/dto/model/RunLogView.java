package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class RunLogView {
    private Long runRecordId;
    private String content;
    private boolean truncated;
    private boolean paged;
    private Long sizeBytes;
    private LocalDateTime updatedAt;
    private String charset;
    private String downloadName;
    private String contentType;
    private boolean historicalFallback;
    private Integer pageNo;
    private Integer totalPages;
    private Integer pageSizeBytes;
    private List<InvocationLogSectionView> sections = new ArrayList<InvocationLogSectionView>();
}
