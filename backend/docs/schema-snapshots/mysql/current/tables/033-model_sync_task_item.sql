create table if not exists model_sync_task_item (
  id bigint primary key,
  tenant_id varchar(64) default 'default',
  project_id bigint,
  deleted int default 0,
  created_at datetime default current_timestamp,
  updated_at datetime default current_timestamp,
  task_id bigint not null,
  seq_no int not null,
  physical_locator varchar(255),
  model_name_snapshot varchar(255),
  status varchar(64),
  message varchar(2000),
  started_at datetime,
  finished_at datetime,
  duration_ms bigint,
  key idx_model_sync_task_item_task_seq (task_id, seq_no),
  key idx_model_sync_task_item_task_status (task_id, status)
);
