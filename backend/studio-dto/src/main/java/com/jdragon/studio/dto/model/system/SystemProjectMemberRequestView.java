package com.jdragon.studio.dto.model.system;

import com.jdragon.studio.dto.model.BaseDefinition;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SystemProjectMemberRequestView extends BaseDefinition {
    private Long userId;
    private String username;
    private String displayName;
    private String projectName;
    private String requestType;
    private String status;
    private Long inviterUserId;
    private String inviterUsername;
    private Long reviewerUserId;
    private String reviewerUsername;
    private String reason;
    private String reviewComment;
}
