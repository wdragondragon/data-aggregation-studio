package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("so_pf_managed_file_ref")
public class ManagedFileReferenceEntity extends BaseProjectTenantEntity {
    private Long fileId;
    private String ownerType;
    private Long ownerId;
    private String fieldKey;
    private Integer ordinal;
}
