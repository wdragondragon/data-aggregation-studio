-- Single-cluster file transfer compatibility fields. Existing source/target cluster
-- columns remain for historical records and are intentionally not removed.
set @schema_name = database();

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'datasource_definition' and column_name = 'created_by') = 0,
  'alter table datasource_definition add column created_by bigint after updated_at',
  'select 1'); prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'file_transfer_task_definition' and column_name = 'runtime_cluster_id') = 0,
  'alter table file_transfer_task_definition add column runtime_cluster_id bigint after published_version',
  'select 1'); prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'file_transfer_run' and column_name = 'runtime_cluster_id') = 0,
  'alter table file_transfer_run add column runtime_cluster_id bigint after status',
  'select 1'); prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'file_transfer_run_item' and column_name = 'runtime_cluster_id') = 0,
  'alter table file_transfer_run_item add column runtime_cluster_id bigint after channel',
  'select 1'); prepare stmt from @sql; execute stmt; deallocate prepare stmt;

update file_transfer_task_definition
set runtime_cluster_id = source_runtime_cluster_id
where runtime_cluster_id is null
  and source_runtime_cluster_id is not null
  and source_runtime_cluster_id = target_runtime_cluster_id;

update file_transfer_run
set runtime_cluster_id = source_runtime_cluster_id
where runtime_cluster_id is null
  and source_runtime_cluster_id is not null
  and source_runtime_cluster_id = target_runtime_cluster_id;

update file_transfer_run_item
set runtime_cluster_id = source_runtime_cluster_id
where runtime_cluster_id is null
  and source_runtime_cluster_id is not null
  and source_runtime_cluster_id = target_runtime_cluster_id;
