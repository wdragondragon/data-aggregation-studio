package com.jdragon.studio.dto.model;

import com.jdragon.studio.dto.enums.CollectionTaskType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CollectionTaskWorkflowOptionView {
    private Long id;
    private Long projectId;
    private LocalDateTime updatedAt;
    private String name;
    private CollectionTaskType taskType;
    private Integer sourceCount;
}
