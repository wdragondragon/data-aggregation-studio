package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class FileTransferQueueEventView {
    private Long eventId;
    private String type;
    private Long runId;
    private Long itemId;
    private FileTransferRunView run;
    private List<FileTransferRunItemView> items = new ArrayList<FileTransferRunItemView>();
    private LocalDateTime occurredAt;
}
