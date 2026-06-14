-- Data Aggregation Studio 增量数据脚本
-- 目标：协议转换服务是一入一出的协议转发，不按采集 writer 语义拆分批次。
-- 说明：payload_mode=ARRAY 仅表示目标 Body 是数组结构，batch_size 固定归一为 1。

update protocol_conversion_service
set batch_size = 1,
    updated_at = current_timestamp
where batch_size is null or batch_size <> 1;
