-- P0-KS-01: Studio Native Kafka streaming schema.
-- Historical collection tasks remain BATCH and no deployment is created by migration.

set @schema_name = database();

set @sql = if((select count(*) from information_schema.columns where table_schema=@schema_name and table_name='collection_task_definition' and column_name='execution_mode')=0,
  'alter table collection_task_definition add column execution_mode varchar(32) not null default ''BATCH''', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

set @sql = if((select count(*) from information_schema.columns where table_schema=@schema_name and table_name='collection_task_definition' and column_name='streaming_options_json')=0,
  'alter table collection_task_definition add column streaming_options_json json', 'select 1');
prepare stmt from @sql; execute stmt; deallocate prepare stmt;

update collection_task_definition
set execution_mode='BATCH'
where execution_mode is null or trim(execution_mode)='';

create table if not exists stream_task_deploy (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    collection_task_id bigint not null,
    runtime_cluster_id bigint,
    generation bigint not null default 0,
    desired_state varchar(32) not null default 'STOPPED',
    observed_state varchar(32) not null default 'STOPPED',
    current_run_id bigint,
    current_attempt_id bigint,
    consecutive_failure_count int not null default 0,
    next_retry_at datetime,
    last_checkpoint_json json,
    last_checkpoint_at datetime,
    last_error_code varchar(128),
    last_error_summary varchar(1000),
    version int not null default 0,
    unique key uk_stream_deploy_task (tenant_id, project_id, collection_task_id),
    key idx_stream_deploy_state (tenant_id, runtime_cluster_id, desired_state, observed_state, next_retry_at)
);

create table if not exists stream_task_run (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    collection_task_id bigint not null,
    generation bigint not null,
    runtime_cluster_id bigint,
    status varchar(32) not null,
    delivery_semantics varchar(32) not null default 'AT_LEAST_ONCE',
    group_id varchar(255) not null,
    started_by bigint,
    started_at datetime,
    stop_requested_at datetime,
    stopped_by bigint,
    stopped_at datetime,
    stop_reason varchar(1000),
    final_checkpoint_json json,
    unique key uk_stream_run_task_gen (tenant_id, project_id, collection_task_id, generation),
    key idx_stream_run_task_time (tenant_id, project_id, collection_task_id, started_at)
);

create table if not exists stream_task_attempt (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    run_id bigint not null,
    collection_task_id bigint not null,
    generation bigint not null,
    attempt_no int not null,
    dispatch_task_id bigint,
    run_record_id bigint,
    runtime_cluster_id bigint,
    worker_instance_id varchar(255),
    worker_boot_id varchar(128),
    status varchar(32) not null,
    started_at datetime,
    ended_at datetime,
    heartbeat_at datetime,
    retry_after datetime,
    checkpoint_json json,
    error_code varchar(128),
    error_summary varchar(1000),
    committed_batch_count bigint not null default 0,
    unique key uk_stream_attempt_run_no (tenant_id, project_id, run_id, attempt_no),
    key idx_stream_attempt_task_status (tenant_id, project_id, collection_task_id, status, updated_at)
);

create table if not exists stream_metric_bucket (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    collection_task_id bigint not null,
    run_id bigint not null,
    attempt_id bigint not null,
    bucket_start datetime not null,
    records_read bigint not null default 0,
    write_succeed_records bigint not null default 0,
    write_failed_records bigint not null default 0,
    dirty_records bigint not null default 0,
    bytes_read bigint not null default 0,
    batch_count bigint not null default 0,
    retry_count bigint not null default 0,
    current_lag bigint not null default 0,
    max_lag bigint not null default 0,
    last_message_at datetime,
    last_checkpoint_at datetime,
    rebalance_count bigint not null default 0,
    unique key uk_stream_metric_attempt_min (tenant_id, project_id, attempt_id, bucket_start),
    key idx_stream_metric_task_time (tenant_id, project_id, collection_task_id, bucket_start)
);

create table if not exists stream_task_event (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    collection_task_id bigint not null,
    deployment_id bigint,
    run_id bigint,
    attempt_id bigint,
    generation bigint,
    event_type varchar(64) not null,
    from_state varchar(32),
    to_state varchar(32),
    message varchar(1000),
    details_json json,
    actor_id bigint,
    occurred_at datetime not null,
    key idx_stream_event_task_time (tenant_id, project_id, collection_task_id, occurred_at),
    key idx_stream_event_run_attempt (run_id, attempt_id, occurred_at)
);

create table if not exists run_log_chunk (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    collection_task_id bigint,
    run_record_id bigint,
    stream_attempt_id bigint,
    sequence_no int not null,
    status varchar(32) not null,
    local_path varchar(1000),
    storage_type varchar(32),
    object_bucket varchar(255),
    object_key varchar(1000),
    size_bytes bigint not null default 0,
    checksum_sha256 varchar(64),
    chunk_started_at datetime,
    chunk_ended_at datetime,
    uploaded_at datetime,
    unique key uk_run_log_chunk_attempt_seq (tenant_id, project_id, stream_attempt_id, sequence_no),
    key idx_run_log_chunk_record_seq (run_record_id, sequence_no),
    key idx_run_log_chunk_task_time (collection_task_id, chunk_started_at)
);
