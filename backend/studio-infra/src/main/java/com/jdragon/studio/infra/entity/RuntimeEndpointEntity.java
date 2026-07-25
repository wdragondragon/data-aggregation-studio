package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("studio_runtime_endpoint")
public class RuntimeEndpointEntity extends BaseTenantEntity {
    private Long runtimeClusterId;
    private String mode;
    private String endpointCiphertext;
    private String headersCiphertext;
    private String tokenCiphertext;
    private Integer connectTimeoutMillis;
    private Integer readTimeoutMillis;
    private Integer enabled;
    private LocalDateTime lastTestedAt;
    private String lastTestStatus;
    private String lastTestMessage;
}
