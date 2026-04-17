package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("quality_issue_comment")
public class QualityIssueCommentEntity extends BaseProjectTenantEntity {
    private Long issueId;
    private Long authorUserId;
    private String authorNameSnapshot;
    private String content;
}
