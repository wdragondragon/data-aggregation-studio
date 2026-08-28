package com.jdragon.studio.infra.service;

import org.springframework.jdbc.core.JdbcTemplate;

final class StudioStreamingSchemaUpgradeSupport {

    private final JdbcTemplate jdbcTemplate;
    private final StudioSchemaIntrospector schemaIntrospector;

    StudioStreamingSchemaUpgradeSupport(JdbcTemplate jdbcTemplate,
                                        StudioSchemaIntrospector schemaIntrospector) {
        this.jdbcTemplate = jdbcTemplate;
        this.schemaIntrospector = schemaIntrospector;
    }

    void ensureMysql() {
        ensureDefinitionColumns(
                "alter table collection_task_definition add column execution_mode varchar(32) not null default 'BATCH'",
                "alter table collection_task_definition add column streaming_options_json json");
        createMysqlTables();
        ensureMysqlIndexes();
        backfillBatchMode();
    }

    void ensureSqlite() {
        ensureDefinitionColumns(
                "alter table collection_task_definition add column execution_mode text not null default 'BATCH'",
                "alter table collection_task_definition add column streaming_options_json text");
        createSqliteTables();
        ensureSqliteIndexes();
        backfillBatchMode();
    }

    private void ensureDefinitionColumns(String executionModeDdl, String optionsDdl) {
        if (!schemaIntrospector.tableExists("collection_task_definition")) {
            return;
        }
        schemaIntrospector.ensureColumn("collection_task_definition", "execution_mode", executionModeDdl);
        schemaIntrospector.ensureColumn("collection_task_definition", "streaming_options_json", optionsDdl);
    }

    private void backfillBatchMode() {
        if (!schemaIntrospector.tableExists("collection_task_definition")
                || !schemaIntrospector.columnExists("collection_task_definition", "execution_mode")) {
            return;
        }
        jdbcTemplate.update("update collection_task_definition set execution_mode='BATCH' "
                + "where execution_mode is null or trim(execution_mode)=''");
    }

    private void createMysqlTables() {
        jdbcTemplate.execute("create table if not exists stream_task_deploy ("
                + "id bigint primary key, tenant_id varchar(64) default 'default', project_id bigint, deleted int default 0,"
                + "created_at datetime default current_timestamp, updated_at datetime default current_timestamp,"
                + "collection_task_id bigint not null, runtime_cluster_id bigint, generation bigint not null default 0,"
                + "desired_state varchar(32) not null default 'STOPPED', observed_state varchar(32) not null default 'STOPPED',"
                + "current_run_id bigint, current_attempt_id bigint, consecutive_failure_count int not null default 0,"
                + "next_retry_at datetime, last_checkpoint_json json, last_checkpoint_at datetime,"
                + "last_error_code varchar(128), last_error_summary varchar(1000), version int not null default 0)");
        jdbcTemplate.execute("create table if not exists stream_task_run ("
                + "id bigint primary key, tenant_id varchar(64) default 'default', project_id bigint, deleted int default 0,"
                + "created_at datetime default current_timestamp, updated_at datetime default current_timestamp,"
                + "collection_task_id bigint not null, generation bigint not null, runtime_cluster_id bigint,"
                + "status varchar(32) not null, delivery_semantics varchar(32) not null default 'AT_LEAST_ONCE',"
                + "group_id varchar(255) not null, started_by bigint, started_at datetime, stop_requested_at datetime,"
                + "stopped_by bigint, stopped_at datetime, stop_reason varchar(1000), final_checkpoint_json json)");
        jdbcTemplate.execute("create table if not exists stream_task_attempt ("
                + "id bigint primary key, tenant_id varchar(64) default 'default', project_id bigint, deleted int default 0,"
                + "created_at datetime default current_timestamp, updated_at datetime default current_timestamp,"
                + "run_id bigint not null, collection_task_id bigint not null, generation bigint not null, attempt_no int not null,"
                + "dispatch_task_id bigint, run_record_id bigint, runtime_cluster_id bigint, worker_instance_id varchar(255),"
                + "worker_boot_id varchar(128), status varchar(32) not null, started_at datetime, ended_at datetime,"
                + "heartbeat_at datetime, retry_after datetime, checkpoint_json json, error_code varchar(128),"
                + "error_summary varchar(1000), committed_batch_count bigint not null default 0)");
        jdbcTemplate.execute("create table if not exists stream_metric_bucket ("
                + "id bigint primary key, tenant_id varchar(64) default 'default', project_id bigint, deleted int default 0,"
                + "created_at datetime default current_timestamp, updated_at datetime default current_timestamp,"
                + "collection_task_id bigint not null, run_id bigint not null, attempt_id bigint not null, bucket_start datetime not null,"
                + "records_read bigint not null default 0, write_succeed_records bigint not null default 0,"
                + "write_failed_records bigint not null default 0, dirty_records bigint not null default 0,"
                + "bytes_read bigint not null default 0, batch_count bigint not null default 0, retry_count bigint not null default 0,"
                + "current_lag bigint not null default 0, max_lag bigint not null default 0, last_message_at datetime,"
                + "last_checkpoint_at datetime, rebalance_count bigint not null default 0)");
        jdbcTemplate.execute("create table if not exists stream_task_event ("
                + "id bigint primary key, tenant_id varchar(64) default 'default', project_id bigint, deleted int default 0,"
                + "created_at datetime default current_timestamp, updated_at datetime default current_timestamp,"
                + "collection_task_id bigint not null, deployment_id bigint, run_id bigint, attempt_id bigint, generation bigint,"
                + "event_type varchar(64) not null, from_state varchar(32), to_state varchar(32), message varchar(1000),"
                + "details_json json, actor_id bigint, occurred_at datetime not null)");
        jdbcTemplate.execute("create table if not exists run_log_chunk ("
                + "id bigint primary key, tenant_id varchar(64) default 'default', project_id bigint, deleted int default 0,"
                + "created_at datetime default current_timestamp, updated_at datetime default current_timestamp,"
                + "collection_task_id bigint, run_record_id bigint, stream_attempt_id bigint, sequence_no int not null,"
                + "status varchar(32) not null, local_path varchar(1000), storage_type varchar(32), object_bucket varchar(255),"
                + "object_key varchar(1000), size_bytes bigint not null default 0, checksum_sha256 varchar(64),"
                + "chunk_started_at datetime, chunk_ended_at datetime, uploaded_at datetime)");
    }

    private void createSqliteTables() {
        jdbcTemplate.execute("create table if not exists stream_task_deploy ("
                + "id integer primary key, tenant_id text default 'default', project_id integer, deleted integer default 0,"
                + "created_at text, updated_at text, collection_task_id integer not null, runtime_cluster_id integer,"
                + "generation integer not null default 0, desired_state text not null default 'STOPPED',"
                + "observed_state text not null default 'STOPPED', current_run_id integer, current_attempt_id integer,"
                + "consecutive_failure_count integer not null default 0, next_retry_at text, last_checkpoint_json text,"
                + "last_checkpoint_at text, last_error_code text, last_error_summary text, version integer not null default 0)");
        jdbcTemplate.execute("create table if not exists stream_task_run ("
                + "id integer primary key, tenant_id text default 'default', project_id integer, deleted integer default 0,"
                + "created_at text, updated_at text, collection_task_id integer not null, generation integer not null,"
                + "runtime_cluster_id integer, status text not null, delivery_semantics text not null default 'AT_LEAST_ONCE',"
                + "group_id text not null, started_by integer, started_at text, stop_requested_at text, stopped_by integer,"
                + "stopped_at text, stop_reason text, final_checkpoint_json text)");
        jdbcTemplate.execute("create table if not exists stream_task_attempt ("
                + "id integer primary key, tenant_id text default 'default', project_id integer, deleted integer default 0,"
                + "created_at text, updated_at text, run_id integer not null, collection_task_id integer not null,"
                + "generation integer not null, attempt_no integer not null, dispatch_task_id integer, run_record_id integer,"
                + "runtime_cluster_id integer, worker_instance_id text, worker_boot_id text, status text not null,"
                + "started_at text, ended_at text, heartbeat_at text, retry_after text, checkpoint_json text,"
                + "error_code text, error_summary text, committed_batch_count integer not null default 0)");
        jdbcTemplate.execute("create table if not exists stream_metric_bucket ("
                + "id integer primary key, tenant_id text default 'default', project_id integer, deleted integer default 0,"
                + "created_at text, updated_at text, collection_task_id integer not null, run_id integer not null,"
                + "attempt_id integer not null, bucket_start text not null, records_read integer not null default 0,"
                + "write_succeed_records integer not null default 0, write_failed_records integer not null default 0,"
                + "dirty_records integer not null default 0, bytes_read integer not null default 0,"
                + "batch_count integer not null default 0, retry_count integer not null default 0,"
                + "current_lag integer not null default 0, max_lag integer not null default 0, last_message_at text,"
                + "last_checkpoint_at text, rebalance_count integer not null default 0)");
        jdbcTemplate.execute("create table if not exists stream_task_event ("
                + "id integer primary key, tenant_id text default 'default', project_id integer, deleted integer default 0,"
                + "created_at text, updated_at text, collection_task_id integer not null, deployment_id integer, run_id integer,"
                + "attempt_id integer, generation integer, event_type text not null, from_state text, to_state text,"
                + "message text, details_json text, actor_id integer, occurred_at text not null)");
        jdbcTemplate.execute("create table if not exists run_log_chunk ("
                + "id integer primary key, tenant_id text default 'default', project_id integer, deleted integer default 0,"
                + "created_at text, updated_at text, collection_task_id integer, run_record_id integer, stream_attempt_id integer,"
                + "sequence_no integer not null, status text not null, local_path text, storage_type text, object_bucket text,"
                + "object_key text, size_bytes integer not null default 0, checksum_sha256 text, chunk_started_at text,"
                + "chunk_ended_at text, uploaded_at text)");
    }

    private void ensureMysqlIndexes() {
        ensureIndex("stream_task_deploy", "uk_stream_deploy_task",
                "alter table stream_task_deploy add unique key uk_stream_deploy_task (tenant_id, project_id, collection_task_id)");
        ensureIndex("stream_task_deploy", "idx_stream_deploy_state",
                "alter table stream_task_deploy add key idx_stream_deploy_state (tenant_id, runtime_cluster_id, desired_state, observed_state, next_retry_at)");
        ensureIndex("stream_task_run", "uk_stream_run_task_gen",
                "alter table stream_task_run add unique key uk_stream_run_task_gen (tenant_id, project_id, collection_task_id, generation)");
        ensureIndex("stream_task_run", "idx_stream_run_task_time",
                "alter table stream_task_run add key idx_stream_run_task_time (tenant_id, project_id, collection_task_id, started_at)");
        ensureIndex("stream_task_attempt", "uk_stream_attempt_run_no",
                "alter table stream_task_attempt add unique key uk_stream_attempt_run_no (tenant_id, project_id, run_id, attempt_no)");
        ensureIndex("stream_task_attempt", "idx_stream_attempt_task_status",
                "alter table stream_task_attempt add key idx_stream_attempt_task_status (tenant_id, project_id, collection_task_id, status, updated_at)");
        ensureIndex("stream_metric_bucket", "uk_stream_metric_attempt_min",
                "alter table stream_metric_bucket add unique key uk_stream_metric_attempt_min (tenant_id, project_id, attempt_id, bucket_start)");
        ensureIndex("stream_metric_bucket", "idx_stream_metric_task_time",
                "alter table stream_metric_bucket add key idx_stream_metric_task_time (tenant_id, project_id, collection_task_id, bucket_start)");
        ensureIndex("stream_task_event", "idx_stream_event_task_time",
                "alter table stream_task_event add key idx_stream_event_task_time (tenant_id, project_id, collection_task_id, occurred_at)");
        ensureIndex("stream_task_event", "idx_stream_event_run_attempt",
                "alter table stream_task_event add key idx_stream_event_run_attempt (run_id, attempt_id, occurred_at)");
        ensureIndex("run_log_chunk", "uk_run_log_chunk_attempt_seq",
                "alter table run_log_chunk add unique key uk_run_log_chunk_attempt_seq (tenant_id, project_id, stream_attempt_id, sequence_no)");
        ensureIndex("run_log_chunk", "idx_run_log_chunk_record_seq",
                "alter table run_log_chunk add key idx_run_log_chunk_record_seq (run_record_id, sequence_no)");
        ensureIndex("run_log_chunk", "idx_run_log_chunk_task_time",
                "alter table run_log_chunk add key idx_run_log_chunk_task_time (collection_task_id, chunk_started_at)");
    }

    private void ensureSqliteIndexes() {
        ensureIndex("stream_task_deploy", "uk_stream_deploy_task",
                "create unique index uk_stream_deploy_task on stream_task_deploy(tenant_id, project_id, collection_task_id)");
        ensureIndex("stream_task_deploy", "idx_stream_deploy_state",
                "create index idx_stream_deploy_state on stream_task_deploy(tenant_id, runtime_cluster_id, desired_state, observed_state, next_retry_at)");
        ensureIndex("stream_task_run", "uk_stream_run_task_gen",
                "create unique index uk_stream_run_task_gen on stream_task_run(tenant_id, project_id, collection_task_id, generation)");
        ensureIndex("stream_task_run", "idx_stream_run_task_time",
                "create index idx_stream_run_task_time on stream_task_run(tenant_id, project_id, collection_task_id, started_at)");
        ensureIndex("stream_task_attempt", "uk_stream_attempt_run_no",
                "create unique index uk_stream_attempt_run_no on stream_task_attempt(tenant_id, project_id, run_id, attempt_no)");
        ensureIndex("stream_task_attempt", "idx_stream_attempt_task_status",
                "create index idx_stream_attempt_task_status on stream_task_attempt(tenant_id, project_id, collection_task_id, status, updated_at)");
        ensureIndex("stream_metric_bucket", "uk_stream_metric_attempt_min",
                "create unique index uk_stream_metric_attempt_min on stream_metric_bucket(tenant_id, project_id, attempt_id, bucket_start)");
        ensureIndex("stream_metric_bucket", "idx_stream_metric_task_time",
                "create index idx_stream_metric_task_time on stream_metric_bucket(tenant_id, project_id, collection_task_id, bucket_start)");
        ensureIndex("stream_task_event", "idx_stream_event_task_time",
                "create index idx_stream_event_task_time on stream_task_event(tenant_id, project_id, collection_task_id, occurred_at)");
        ensureIndex("stream_task_event", "idx_stream_event_run_attempt",
                "create index idx_stream_event_run_attempt on stream_task_event(run_id, attempt_id, occurred_at)");
        ensureIndex("run_log_chunk", "uk_run_log_chunk_attempt_seq",
                "create unique index uk_run_log_chunk_attempt_seq on run_log_chunk(tenant_id, project_id, stream_attempt_id, sequence_no)");
        ensureIndex("run_log_chunk", "idx_run_log_chunk_record_seq",
                "create index idx_run_log_chunk_record_seq on run_log_chunk(run_record_id, sequence_no)");
        ensureIndex("run_log_chunk", "idx_run_log_chunk_task_time",
                "create index idx_run_log_chunk_task_time on run_log_chunk(collection_task_id, chunk_started_at)");
    }

    private void ensureIndex(String tableName, String indexName, String ddl) {
        schemaIntrospector.ensureIndex(tableName, indexName, ddl);
    }
}
