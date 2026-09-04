package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("so_pf_managed_file_lease")
public class ManagedFileLeaseEntity extends BaseProjectTenantEntity {
    private Long fileId;
    private String leaseToken;
    private String consumerType;
    private String consumerId;
    private String workerInstanceId;
    private LocalDateTime heartbeatAt;
    private LocalDateTime expiresAt;
    private LocalDateTime releasedAt;
}
