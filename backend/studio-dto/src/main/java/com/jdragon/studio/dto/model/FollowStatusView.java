package com.jdragon.studio.dto.model;

import lombok.Data;

@Data
public class FollowStatusView {
    private String targetType;
    private Long targetId;
    private boolean following;
}
