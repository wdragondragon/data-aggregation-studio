package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("quality_task_alert")
public class QualityTaskAlertEntity extends BaseProjectTenantEntity {
    private Long qualityTaskId;
    private Integer outputOrder;
    private String resultField;
    private String outputType;
    private Integer enabled;
    private String operator;
    private String expectedValue;
    private String minValue;
    private String maxValue;
}
