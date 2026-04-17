package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "quality_issue_event", autoResultMap = true)
public class QualityIssueEventEntity extends BaseProjectTenantEntity {
    private Long issueId;
    private String eventType;
    private String eventTitle;
    private String eventMessage;
    private Long actorUserId;
    private String actorNameSnapshot;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadataJson = new LinkedHashMap<String, Object>();
}
