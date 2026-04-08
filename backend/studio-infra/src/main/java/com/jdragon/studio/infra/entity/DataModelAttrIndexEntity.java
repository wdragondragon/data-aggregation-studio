package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("data_model_attr_index")
public class DataModelAttrIndexEntity extends BaseProjectTenantEntity {
    private Long modelId;
    private Long datasourceId;
    private Long metaSchemaVersionId;
    private String metaSchemaCode;
    private String scope;
    private String metaModelCode;
    private String itemKey;
    private String fieldKey;
    private String valueType;
    private String keywordValue;
    private String textValue;
    private BigDecimal numberValue;
    private Integer boolValue;
    private String rawValue;
}
