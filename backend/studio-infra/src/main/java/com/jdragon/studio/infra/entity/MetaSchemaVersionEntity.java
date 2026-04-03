package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("meta_schema_version")
public class MetaSchemaVersionEntity extends BaseTenantEntity {
    private Long schemaId;
    private Integer versionNumber;
    private String status;
    private String description;
}
