package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("data_dev_script")
public class DataDevelopmentScriptEntity extends BaseTenantEntity {
    private Long directoryId;
    private String fileName;
    private String scriptType;
    private Long datasourceId;
    private String description;
    private String content;
}
