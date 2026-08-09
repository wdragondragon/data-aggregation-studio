package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class FileTransferScheduleDefinition {
    private Boolean enabled = Boolean.FALSE;
    private String cronExpression;
    private String timezone = "Asia/Shanghai";
}
