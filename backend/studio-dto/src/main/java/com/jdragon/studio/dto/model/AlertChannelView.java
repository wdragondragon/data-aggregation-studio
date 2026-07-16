package com.jdragon.studio.dto.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class AlertChannelView {
    private Long id;
    private String name;
    private String channelType;
    private String endpointMasked;
    private List<String> headerNames = new ArrayList<String>();
    private boolean hasSigningSecret;
    private String elinkTargetType;
    private List<String> elinkUserIds = new ArrayList<String>();
    private Long elinkGroupId;
    private boolean enabled;
    private LocalDateTime lastTestedAt;
    private String lastTestStatus;
    private String lastTestMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
