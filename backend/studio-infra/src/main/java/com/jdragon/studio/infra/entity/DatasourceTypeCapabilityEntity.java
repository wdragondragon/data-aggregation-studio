package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "datasource_type_capability", autoResultMap = true)
public class DatasourceTypeCapabilityEntity extends BaseTenantEntity {
    private String typeCode;
    private String typeName;
    private Integer enabled;
    private Integer readable;
    private Integer writable;
    private Integer executable;
    private Integer sqlExecutable;
    private String sourceCategory;
    private String sourcePlugin;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> readerPluginsJson = new ArrayList<String>();

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> writerPluginsJson = new ArrayList<String>();

    private Integer sortOrder;
    private String description;
}
