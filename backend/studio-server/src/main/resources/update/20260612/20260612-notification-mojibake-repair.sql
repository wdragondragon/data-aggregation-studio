-- Data Aggregation Studio 增量数据脚本
-- 目标：修复历史采集任务运行站内信正文中已保存的 UTF-8/Latin-1 乱码。
-- 说明：采集任务运行通知正文可由 run_record 与 collection_task_definition 无损重建，重复执行安全。

update studio_notification n
join run_record r on n.target_id = r.id and r.collection_task_id is not null
join collection_task_definition t on r.collection_task_id = t.id
set n.content = concat('采集任务 ', t.name, ' 本次运行状态为 ', r.status, '。'),
    n.updated_at = current_timestamp
where n.category = 'COLLECTION_TASK_RUN'
  and n.target_type = 'COLLECTION_TASK_RUN';
