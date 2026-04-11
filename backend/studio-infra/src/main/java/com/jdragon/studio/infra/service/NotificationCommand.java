package com.jdragon.studio.infra.service;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Accessors(chain = true)
public class NotificationCommand {
    private String category;
    private String title;
    private String content;
    private String targetType;
    private Long targetId;
    private String targetPath;
    private String targetTenantId;
    private Long targetProjectId;
    private String dedupeKey;
    private Map<String, Object> payloadJson = new LinkedHashMap<String, Object>();
}
