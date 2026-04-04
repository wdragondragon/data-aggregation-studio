package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RunLogView {
    private Long runRecordId;
    private String content;
    private boolean truncated;
    private Long sizeBytes;
    private LocalDateTime updatedAt;
    private String charset;
    private String downloadName;
    private String contentType;
    private boolean historicalFallback;
}
