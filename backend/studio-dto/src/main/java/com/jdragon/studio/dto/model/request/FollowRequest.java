package com.jdragon.studio.dto.model.request;

import lombok.Data;

@Data
public class FollowRequest {
    private String targetType;
    private Long targetId;
}
