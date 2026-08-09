package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("file_transfer_metric_sample")
public class FileTransferMetricSampleEntity extends BaseProjectTenantEntity {
    private Long runId;
    private Long runRecordId;
    private Long taskId;
    private Long runtimeClusterId;
    private Long sourceDatasourceId;
    private Long targetDatasourceId;
    private String channel;
    private String status;
    private LocalDateTime sampledAt;
    private Long transferredBytes;
    private Long bytesPerSecond;
    private Long completedFiles;
    private Long failedFiles;
    private Integer activeFiles;
    private Integer retryCount;
}
