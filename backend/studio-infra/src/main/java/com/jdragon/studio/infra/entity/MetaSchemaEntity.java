package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("meta_schema")
public class MetaSchemaEntity extends BaseTenantEntity {
    private String schemaCode;
    private String schemaName;
    private String objectType;
    private String typeCode;
    private Long currentVersionId;
    private String status;
    private String description;
}
