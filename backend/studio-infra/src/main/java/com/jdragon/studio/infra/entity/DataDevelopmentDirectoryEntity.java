package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("data_dev_directory")
public class DataDevelopmentDirectoryEntity extends BaseTenantEntity {
    private Long parentId;
    private String name;
    private String permissionCode;
    private String description;
}
