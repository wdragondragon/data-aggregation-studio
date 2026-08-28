create table if not exists sys_user (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    username text not null unique,
    password_hash text not null,
    display_name text,
    mobile_phone text,
    auth_source text default 'LOCAL',
    enabled integer default 1
);

create table if not exists studio_external_user_binding (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    provider_code text not null,
    external_user_id text not null,
    external_account text,
    studio_user_id integer not null,
    last_seen_at text
);

create unique index if not exists uk_studio_external_user_binding_external
    on studio_external_user_binding(provider_code, external_user_id);
create unique index if not exists uk_studio_external_user_binding_provider_user
    on studio_external_user_binding(provider_code, studio_user_id);
create index if not exists idx_studio_external_user_binding_user
    on studio_external_user_binding(studio_user_id);

create table if not exists sys_role (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    code text not null unique,
    name text not null,
    description text
);

create table if not exists sys_permission (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    code text not null unique,
    name text not null,
    http_method text,
    path_pattern text
);

create table if not exists sys_user_role (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    user_id integer,
    role_id integer
);

create table if not exists sys_role_permission (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    role_id integer,
    permission_id integer
);

create table if not exists studio_tenant (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    tenant_code text not null,
    tenant_name text not null,
    description text,
    enabled integer default 1
);

create unique index if not exists uk_studio_tenant_code on studio_tenant(tenant_code);

create table if not exists studio_project (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    project_code text not null,
    project_name text not null,
    description text,
    enabled integer default 1,
    default_project integer default 0
);

create unique index if not exists uk_studio_project_code on studio_project(tenant_id, project_code);
create unique index if not exists uk_studio_project_name on studio_project(tenant_id, project_name);

create table if not exists studio_runtime_cluster (
    id integer primary key, tenant_id text default 'default', deleted integer default 0,
    created_at text, updated_at text, code text not null, name text not null,
    enabled integer default 1, status text default 'UNKNOWN', version text,
    last_heartbeat_at text, instances_json text
);
create unique index if not exists uk_runtime_cluster_tenant_code on studio_runtime_cluster(tenant_id, code);
create index if not exists idx_runtime_cluster_tenant_enabled on studio_runtime_cluster(tenant_id, enabled);

create table if not exists studio_runtime_endpoint (
    id integer primary key, tenant_id text default 'default', deleted integer default 0,
    created_at text, updated_at text, runtime_cluster_id integer not null, mode text not null,
    endpoint_ciphertext text, headers_ciphertext text, token_ciphertext text,
    connect_timeout_millis integer default 3000, read_timeout_millis integer default 5000,
    enabled integer default 1, last_tested_at text, last_test_status text, last_test_message text
);
create index if not exists idx_runtime_endpoint_cluster on studio_runtime_endpoint(tenant_id, runtime_cluster_id, enabled);

create table if not exists studio_runtime_idempotency (
    id integer primary key, tenant_id text default 'default', project_id integer not null,
    deleted integer default 0, created_at text, updated_at text, runtime_cluster_id integer,
    resource_type text not null, resource_id integer not null, key_hash text not null,
    request_fingerprint text not null, status text not null, owner_token_hash text not null,
    owner_instance_id text not null, owner_boot_id text not null, response_status integer,
    response_content_type text, response_body_ciphertext text, completed_at text,
    version integer default 0
);
create unique index if not exists uk_runtime_idem_scope_key on studio_runtime_idempotency(
    tenant_id, project_id, resource_type, resource_id, key_hash);
create index if not exists idx_runtime_idem_status_updated on studio_runtime_idempotency(status, updated_at);

create table if not exists studio_project_runtime_cluster (
    id integer primary key, tenant_id text default 'default', project_id integer not null,
    deleted integer default 0, created_at text, updated_at text, runtime_cluster_id integer not null,
    enabled integer default 1, preferred integer default 0, allow_manual_override integer default 0
);
create unique index if not exists uk_project_runtime_cluster on studio_project_runtime_cluster(tenant_id, project_id, runtime_cluster_id);
create index if not exists idx_project_runtime_cluster_options on studio_project_runtime_cluster(tenant_id, project_id, enabled, preferred);

create table if not exists studio_tenant_member (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    user_id integer not null,
    role_code text not null,
    status text not null
);

create unique index if not exists uk_studio_tenant_member_user on studio_tenant_member(tenant_id, user_id);

create table if not exists studio_project_member (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    project_id integer not null,
    user_id integer not null,
    role_code text not null,
    status text not null
);

create unique index if not exists uk_studio_project_member_user on studio_project_member(project_id, user_id);

create table if not exists studio_project_member_request (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    project_id integer not null,
    user_id integer not null,
    request_type text not null,
    status text not null,
    inviter_user_id integer,
    reviewer_user_id integer,
    reason text,
    review_comment text
);

create index if not exists idx_studio_project_member_request_lookup on studio_project_member_request(project_id, user_id, status);

create table if not exists studio_project_worker_binding (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    project_id integer not null,
    worker_group_code text,
    worker_code text not null,
    enabled integer default 1
);

create unique index if not exists uk_studio_project_worker_binding on studio_project_worker_binding(project_id, worker_code);
create index if not exists idx_studio_project_worker_group on studio_project_worker_binding(project_id, worker_group_code);

create table if not exists studio_resource_share (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    source_project_id integer not null,
    target_project_id integer not null,
    resource_type text not null,
    resource_id integer not null,
    shared_by_user_id integer,
    enabled integer default 1
);

create unique index if not exists uk_studio_resource_share_target on studio_resource_share(resource_type, resource_id, target_project_id);
create index if not exists idx_studio_resource_share_project on studio_resource_share(target_project_id);

create table if not exists datasource_type_capability (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    type_code text not null,
    type_name text not null,
    enabled integer default 1,
    readable integer default 0,
    writable integer default 0,
    executable integer default 0,
    sql_executable integer default 0,
    source_category text not null default 'DATABASE',
    source_plugin text,
    reader_plugins_json text,
    writer_plugins_json text,
    runtime_capabilities_json text,
    sort_order integer default 0,
    description text
);
create unique index if not exists uk_datasource_type_capability_code on datasource_type_capability(tenant_id, type_code);

insert or ignore into datasource_type_capability (id, tenant_id, deleted, created_at, updated_at, type_code, type_name, source_category, enabled, readable, writable, executable, sql_executable, source_plugin, reader_plugins_json, writer_plugins_json, sort_order, description) values
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'mysql8', 'MySQL 8', 'DATABASE', 1, 1, 1, 1, 1, 'mysql8', '["mysql8"]', '["mysql8"]', 10, 'MySQL 数据库'),
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'oracle', 'Oracle', 'DATABASE', 1, 1, 0, 1, 1, 'oracle', '["oracle"]', '[]', 20, 'Oracle 数据库'),
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'postgres', 'PostgreSQL', 'DATABASE', 1, 1, 1, 1, 1, 'postgres', '["postgresql"]', '["postgresql"]', 30, 'PostgreSQL 数据库'),
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'dm', '达梦数据库', 'DATABASE', 1, 1, 1, 1, 1, 'dm', '["dm"]', '["dm"]', 40, '达梦数据库'),
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'local', 'Local File', 'FILE_SYSTEM', 1, 0, 0, 1, 0, 'local', '[]', '[]', 45, '本地文件系统'),
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'ftp', 'FTP', 'FILE_SYSTEM', 1, 1, 1, 1, 0, 'ftp', '["ftp"]', '["ftp"]', 50, 'FTP 文件数据源'),
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'sftp', 'SFTP', 'FILE_SYSTEM', 1, 1, 1, 1, 0, 'sftp', '["sftp"]', '["sftp"]', 60, 'SFTP 文件数据源'),
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'minio', 'MinIO', 'FILE_SYSTEM', 1, 1, 1, 1, 0, 'minio', '["minio"]', '["minio"]', 70, 'MinIO / OSS 对象存储'),
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'oss', 'Aliyun OSS', 'FILE_SYSTEM', 1, 0, 0, 1, 0, 'oss', '[]', '[]', 75, '阿里云 OSS 对象存储'),
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'kafka', 'Kafka', 'MESSAGE_QUEUE', 1, 1, 1, 1, 0, 'kafka', '["kafka"]', '["kafka"]', 80, 'Kafka 消息队列'),
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'rocketmq', 'RocketMQ', 'MESSAGE_QUEUE', 1, 1, 1, 1, 0, 'rocketmq', '["rocketmq"]', '["rocketmq"]', 90, 'RocketMQ 消息队列'),
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'http', 'HTTP', 'HTTP_API', 1, 1, 1, 1, 0, 'http', '["httpreader"]', '["httpwriter"]', 95, 'HTTP 接口数据源'),
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'rabbitmq', 'RabbitMQ', 'MESSAGE_QUEUE', 1, 0, 0, 1, 0, 'rabbitmq', '[]', '[]', 100, 'RabbitMQ 消息队列'),
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'odps', 'ODPS', 'DATABASE', 1, 1, 1, 1, 1, 'odps', '["odpsreader"]', '["odpswriter"]', 110, 'ODPS / MaxCompute 数据源'),
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'tbds-hdfs', 'TBDS HDFS', 'FILE_SYSTEM', 1, 0, 0, 1, 0, 'tbds-hdfs', '[]', '[]', 120, 'TBDS HDFS 文件系统'),
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'tbds-hdfs3', 'TBDS HDFS3', 'FILE_SYSTEM', 1, 0, 0, 1, 0, 'tbds-hdfs3', '[]', '[]', 130, 'TBDS HDFS3 文件系统'),
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'tbds-hive2', 'TBDS Hive2', 'DATABASE', 1, 1, 0, 1, 1, 'tbds-hive2', '["tbds-hive2"]', '[]', 140, 'TBDS Hive2 数据源'),
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'tbds-hive3', 'TBDS Hive3', 'DATABASE', 1, 0, 0, 1, 1, 'tbds-hive3', '[]', '[]', 150, 'TBDS Hive3 数据源'),
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'influxdb', 'InfluxDB', 'DATABASE', 1, 0, 0, 1, 0, 'influxdb', '[]', '[]', 160, 'InfluxDB 数据源'),
(abs(random()), 'default', 0, datetime('now'), datetime('now'), 'influxdbv1', 'InfluxDB v1', 'DATABASE', 1, 1, 1, 1, 0, 'influxdbv1', '["influxdbv1"]', '["influxdbv1"]', 170, 'InfluxDB v1 数据源');

create table if not exists field_mapping_rule (
    id integer primary key,
    deleted integer default 0,
    created_at text,
    updated_at text,
    mapping_name text not null,
    mapping_type text not null,
    mapping_code text not null,
    enabled integer default 1,
    description text,
    created_by integer
);
create unique index if not exists uk_field_mapping_rule_code on field_mapping_rule(mapping_code);
create index if not exists idx_field_mapping_rule_type_enabled on field_mapping_rule(mapping_type, enabled);
create index if not exists idx_field_mapping_rule_created_at on field_mapping_rule(created_at);

create table if not exists field_mapping_rule_param (
    id integer primary key,
    deleted integer default 0,
    created_at text,
    updated_at text,
    rule_id integer not null,
    param_name text not null,
    param_order integer not null,
    component_type text not null,
    param_value_json text,
    description text
);
create index if not exists idx_field_mapping_rule_param_rule_order on field_mapping_rule_param(rule_id, param_order);
create index if not exists idx_field_mapping_rule_param_rule_name on field_mapping_rule_param(rule_id, param_name);

create table if not exists quality_rule (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    created_by integer,
    rule_name text not null,
    rule_code text not null,
    scope_type text not null,
    rule_dimension text not null,
    description text,
    supported_datasource_types_json text,
    granularity text not null,
    logic_sql text,
    enabled integer default 1
);
create unique index if not exists uk_quality_rule_scope_code on quality_rule(tenant_id, project_id, scope_type, rule_code);
create index if not exists idx_quality_rule_scope_enabled on quality_rule(tenant_id, project_id, scope_type, enabled);
create index if not exists idx_quality_rule_dimension_enabled on quality_rule(rule_dimension, enabled);

create table if not exists quality_rule_input_param (
    id integer primary key,
    deleted integer default 0,
    created_at text,
    updated_at text,
    rule_id integer not null,
    param_order integer not null,
    param_name text not null,
    param_type text not null,
    param_meaning text
);
create index if not exists idx_quality_rule_input_param_rule_order on quality_rule_input_param(rule_id, param_order);
create index if not exists idx_quality_rule_input_param_rule_name on quality_rule_input_param(rule_id, param_name);

create table if not exists quality_rule_output_param (
    id integer primary key,
    deleted integer default 0,
    created_at text,
    updated_at text,
    rule_id integer not null,
    output_order integer not null,
    result_field text not null,
    output_type text not null,
    output_description text
);
create index if not exists idx_quality_rule_output_param_rule_order on quality_rule_output_param(rule_id, output_order);
create index if not exists idx_quality_rule_output_param_rule_field on quality_rule_output_param(rule_id, result_field);

create table if not exists quality_task_definition (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    created_by integer,
    runtime_cluster_id integer,
    task_name text not null,
    task_code text not null,
    status text,
    rule_id integer not null,
    rule_name_snapshot text,
    rule_dimension text,
    granularity text,
    datasource_id integer,
    datasource_name_snapshot text,
    datasource_type_code text,
    model_id integer,
    model_name_snapshot text,
    model_physical_locator text,
    column_name text,
    where_clause text,
    resolved_sql_preview text,
    parameter_bindings_json text,
    rule_snapshot_json text
);
create unique index if not exists uk_quality_task_definition_project_code on quality_task_definition(project_id, task_code);
create unique index if not exists uk_quality_task_definition_project_name on quality_task_definition(project_id, task_name);
create index if not exists idx_quality_task_definition_project_status on quality_task_definition(project_id, status);
create index if not exists idx_quality_task_definition_rule_dimension on quality_task_definition(project_id, rule_dimension);

create table if not exists quality_task_schedule (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    quality_task_id integer,
    cron_expression text,
    enabled integer default 0,
    timezone text,
    last_triggered_at text
);
create index if not exists idx_quality_task_schedule_project on quality_task_schedule(project_id);
create index if not exists idx_quality_task_schedule_task on quality_task_schedule(quality_task_id);

create table if not exists quality_task_alert (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    quality_task_id integer not null,
    output_order integer not null,
    result_field text not null,
    output_type text not null,
    enabled integer default 0,
    operator text,
    expected_value text,
    min_value text,
    max_value text
);
create index if not exists idx_quality_task_alert_task on quality_task_alert(quality_task_id);
create index if not exists idx_quality_task_alert_task_order on quality_task_alert(quality_task_id, output_order);

create table if not exists quality_issue (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    issue_code text,
    signature text not null,
    issue_type text,
    quality_task_id integer,
    quality_task_name_snapshot text,
    rule_id integer,
    rule_name_snapshot text,
    rule_dimension text,
    datasource_id integer,
    datasource_name_snapshot text,
    datasource_type_code text,
    model_id integer,
    model_name_snapshot text,
    model_physical_locator text,
    column_name text,
    output_field text,
    granularity text,
    title text,
    latest_message text,
    severity text,
    system_severity text,
    manual_severity text,
    status text,
    assignee_user_id integer,
    assignee_name_snapshot text,
    first_seen_at text,
    last_seen_at text,
    last_recovery_at text,
    sla_due_at text,
    occurrence_count integer default 0,
    consecutive_failure_count integer default 0,
    reopen_count integer default 0,
    last_run_record_id integer,
    last_run_status text,
    current_evidence_json text
);
create unique index if not exists uk_quality_issue_signature on quality_issue(tenant_id, project_id, signature);
create index if not exists idx_quality_issue_status_severity on quality_issue(project_id, status, severity);
create index if not exists idx_quality_issue_asset on quality_issue(project_id, datasource_id, model_id);
create index if not exists idx_quality_issue_task on quality_issue(project_id, quality_task_id);
create index if not exists idx_quality_issue_last_seen on quality_issue(project_id, last_seen_at);

create table if not exists quality_issue_comment (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    issue_id integer not null,
    author_user_id integer,
    author_name_snapshot text,
    content text
);
create index if not exists idx_quality_issue_comment_issue on quality_issue_comment(issue_id, created_at);

create table if not exists quality_issue_event (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    issue_id integer not null,
    event_type text,
    event_title text,
    event_message text,
    actor_user_id integer,
    actor_name_snapshot text,
    metadata_json text
);
create index if not exists idx_quality_issue_event_issue on quality_issue_event(issue_id, created_at);
create index if not exists idx_quality_issue_event_project_type on quality_issue_event(project_id, event_type, created_at);

create table if not exists quality_metric_snapshot (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    snapshot_date text,
    datasource_id integer,
    datasource_name_snapshot text,
    datasource_type_code text,
    model_id integer,
    model_name_snapshot text,
    model_physical_locator text,
    rule_dimension text,
    execution_health_score integer,
    governance_risk_score integer,
    active_issue_count integer,
    overdue_issue_count integer,
    coverage_rate integer,
    failure_rate integer,
    affected_asset_count integer,
    reopen_rate integer
);
create index if not exists idx_quality_metric_snapshot_project_date on quality_metric_snapshot(project_id, snapshot_date);
create index if not exists idx_quality_metric_snapshot_asset on quality_metric_snapshot(project_id, datasource_id, model_id, snapshot_date);
create index if not exists idx_quality_metric_snapshot_dimension on quality_metric_snapshot(project_id, rule_dimension, snapshot_date);

create table if not exists studio_cluster_lock (
    id integer primary key,
    lock_name text not null,
    owner_id text,
    locked_until text,
    last_acquired_at text,
    created_at text,
    updated_at text
);
create unique index if not exists uk_studio_cluster_lock_name on studio_cluster_lock(lock_name);
create index if not exists idx_studio_cluster_lock_until on studio_cluster_lock(locked_until);

create table if not exists studio_alert_rule (
    id integer primary key,
    tenant_id text default 'default', project_id integer, deleted integer default 0,
    created_at text, updated_at text, name text not null, description text,
    rule_type text not null, subject_type text not null, subject_id integer,
    subject_name_snapshot text, severity text not null, enabled integer default 0,
    condition_json text, silence_minutes integer default 30,
    recovery_notification_enabled integer default 1, in_app_enabled integer default 1,
    recipient_user_ids_json text, notify_resource_owner integer default 0,
    notify_project_admins integer default 1, webhook_channel_ids_json text,
    activation_at text, last_evaluated_at text, last_evaluation_status text,
    last_evaluation_error text, last_triggered_at text, created_by integer, updated_by integer
);
create index if not exists idx_alert_rule_project_enabled on studio_alert_rule(project_id, enabled, rule_type);
create index if not exists idx_alert_rule_project_name on studio_alert_rule(project_id, name);
create unique index if not exists uk_alert_rule_active_name on studio_alert_rule(tenant_id, project_id, name collate nocase) where deleted = 0;

create table if not exists studio_alert_incident (
    id integer primary key,
    tenant_id text default 'default', project_id integer, deleted integer default 0,
    created_at text, updated_at text, rule_id integer not null, rule_name_snapshot text,
    rule_type text, signature text not null, subject_type text, subject_key text,
    subject_id integer, subject_name_snapshot text, target_path text, severity text,
    status text, summary text, requested_cluster_id integer, actual_cluster_id integer,
    current_evidence_json text, occurrence_count integer default 0,
    notification_count integer default 0, reopen_count integer default 0,
    condition_active integer default 0, closed_while_active integer default 0,
    first_triggered_at text, last_triggered_at text, last_notified_at text,
    acknowledged_at text, recovered_at text, closed_at text,
    acknowledged_by integer, closed_by integer, version integer default 0
);
create unique index if not exists uk_alert_incident_signature on studio_alert_incident(tenant_id, project_id, signature);
create index if not exists idx_alert_incident_status on studio_alert_incident(project_id, status, severity, last_triggered_at);
create index if not exists idx_alert_incident_rule on studio_alert_incident(rule_id, last_triggered_at);
create index if not exists idx_alert_incident_subject on studio_alert_incident(project_id, subject_type, subject_id);
create index if not exists idx_alert_incident_cluster on studio_alert_incident(project_id, requested_cluster_id, actual_cluster_id, last_triggered_at);

create table if not exists studio_alert_event (
    id integer primary key,
    tenant_id text default 'default', project_id integer, deleted integer default 0,
    created_at text, updated_at text, incident_id integer, rule_id integer,
    event_type text not null, status_from text, status_to text, source_type text,
    source_id text, source_event_key text not null, subject_type text, subject_key text,
    subject_id integer, subject_name_snapshot text, target_path text, severity text,
    summary text, evidence_json text, actor_user_id integer, actor_name_snapshot text,
    observed_at text
);
create unique index if not exists uk_alert_event_source on studio_alert_event(tenant_id, project_id, source_event_key);
create index if not exists idx_alert_event_incident on studio_alert_event(incident_id, observed_at);
create index if not exists idx_alert_event_rule on studio_alert_event(rule_id, event_type, observed_at);

create table if not exists studio_alert_channel (
    id integer primary key,
    tenant_id text default 'default', project_id integer, deleted integer default 0,
    created_at text, updated_at text, name text not null, channel_type text not null,
    endpoint_ciphertext text, headers_ciphertext text, signing_secret_ciphertext text, config_json text,
    enabled integer default 1, last_tested_at text, last_test_status text,
    last_test_message text, created_by integer, updated_by integer
);
create index if not exists idx_alert_channel_project_enabled on studio_alert_channel(project_id, enabled);
create index if not exists idx_alert_channel_project_name on studio_alert_channel(project_id, name);
create unique index if not exists uk_alert_channel_active_name on studio_alert_channel(tenant_id, project_id, name collate nocase) where deleted = 0;

create table if not exists studio_alert_delivery (
    id integer primary key,
    tenant_id text default 'default', project_id integer, deleted integer default 0,
    created_at text, updated_at text, event_id integer not null, incident_id integer,
    delivery_key text not null, channel_type text not null, channel_id integer,
    channel_name_snapshot text, recipient_user_id integer, status text not null,
    attempt_count integer default 0, next_attempt_at text, last_attempt_at text,
    http_status integer, response_excerpt text, error_message text, payload_json text
);
create unique index if not exists uk_alert_delivery_event_key on studio_alert_delivery(event_id, delivery_key);
create index if not exists idx_alert_delivery_due on studio_alert_delivery(status, next_attempt_at);
create index if not exists idx_alert_delivery_channel on studio_alert_delivery(channel_id, created_at);
create index if not exists idx_alert_delivery_incident on studio_alert_delivery(incident_id, created_at);

create table if not exists user_registration_request (
    id integer primary key,
    deleted integer default 0,
    created_at text,
    updated_at text,
    status text not null,
    username text not null,
    password_hash text not null,
    display_name text,
    reason text,
    review_comment text,
    reviewer_user_id integer,
    approved_user_id integer,
    reviewed_at text
);
create index if not exists idx_user_registration_request_status_created on user_registration_request(status, created_at);
create unique index if not exists uk_user_registration_request_username_status on user_registration_request(username, status);

create table if not exists studio_notification (
    id integer primary key,
    deleted integer default 0,
    created_at text,
    updated_at text,
    recipient_user_id integer not null,
    tenant_id text,
    project_id integer,
    category text,
    title text,
    content text,
    target_type text,
    target_id integer,
    target_path text,
    target_tenant_id text,
    target_project_id integer,
    dedupe_key text,
    read_at text,
    archived_at text,
    payload_json text
);
create index if not exists idx_studio_notification_recipient_created on studio_notification(recipient_user_id, created_at);
create index if not exists idx_studio_notification_recipient_unread on studio_notification(recipient_user_id, read_at, archived_at);
create unique index if not exists uk_studio_notification_recipient_dedupe on studio_notification(recipient_user_id, dedupe_key);

create table if not exists studio_follow_subscription (
    id integer primary key,
    deleted integer default 0,
    created_at text,
    updated_at text,
    tenant_id text default 'default',
    project_id integer,
    user_id integer not null,
    target_type text not null,
    target_id integer not null,
    enabled integer default 1
);
create unique index if not exists uk_studio_follow_subscription_target on studio_follow_subscription(tenant_id, project_id, user_id, target_type, target_id);
create index if not exists idx_studio_follow_subscription_lookup on studio_follow_subscription(target_type, target_id, enabled);

create table if not exists meta_schema (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    schema_code text,
    schema_name text,
    object_type text,
    type_code text,
    current_version_id integer,
    status text,
    description text
);

create table if not exists meta_schema_version (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    schema_id integer,
    version_number integer,
    status text,
    description text
);

create table if not exists meta_field_definition (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    schema_version_id integer,
    field_key text,
    field_name text,
    description text,
    scope text,
    value_type text,
    component_type text,
    required_flag integer default 0,
    sensitive_flag integer default 0,
    sort_order integer default 0,
    validation_rule text,
    placeholder text,
    default_value text,
    searchable_flag integer default 0,
    sortable_flag integer default 0,
    query_operators text,
    query_default_operator text,
    options text
);

create table if not exists datasource_definition (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    created_by integer,
    name text,
    type_code text,
    schema_version_id integer,
    enabled integer default 1,
    executable integer default 0,
    connection_fingerprint text,
    connection_status text default 'UNKNOWN',
    last_connection_test_at text,
    last_connection_test_message text,
    last_connection_test_duration_ms integer,
    manual_connection_test_timeout_seconds integer,
    scheduled_connection_test_timeout_seconds integer,
    technical_metadata text,
    business_metadata text
);
create unique index if not exists uk_datasource_definition_project_name on datasource_definition(project_id, name);
create index if not exists idx_datasource_definition_project on datasource_definition(project_id);
create index if not exists idx_datasource_definition_connection on datasource_definition(tenant_id, connection_fingerprint);

create table if not exists unstructured_source_acl (
    id integer primary key, tenant_id text default 'default', project_id integer not null,
    deleted integer default 0, created_at text, updated_at text, datasource_id integer not null,
    principal_type text not null, user_id integer, permission text not null, effect text not null, created_by integer
);
create unique index if not exists uk_unstructured_source_acl on unstructured_source_acl(tenant_id, datasource_id, principal_type, user_id, permission);
create index if not exists idx_unstructured_source_acl_source on unstructured_source_acl(tenant_id, project_id, datasource_id);

create table if not exists unstructured_path_acl (
    id integer primary key, tenant_id text default 'default', project_id integer not null,
    deleted integer default 0, created_at text, updated_at text, datasource_id integer not null,
    path text not null, directory integer default 1, principal_type text not null, user_id integer, permission text not null, effect text not null, created_by integer
);
create unique index if not exists uk_unstructured_path_acl on unstructured_path_acl(tenant_id, datasource_id, path, principal_type, user_id, permission);
create index if not exists idx_unstructured_path_acl_source on unstructured_path_acl(tenant_id, project_id, datasource_id, path);

create table if not exists unstructured_op_audit (
    id integer primary key, tenant_id text default 'default', project_id integer not null,
    deleted integer default 0, created_at text, updated_at text, datasource_id integer not null,
    runtime_cluster_id integer not null, user_id integer, username text, operation text not null,
    source_path text, target_path text, `recursive` integer default 0, status text not null, message text
);
create index if not exists idx_unstructured_op_audit_source on unstructured_op_audit(tenant_id, project_id, datasource_id, created_at);

create table if not exists datasource_cluster_binding (
    id integer primary key, tenant_id text default 'default', deleted integer default 0,
    created_at text, updated_at text, datasource_id integer not null,
    runtime_cluster_id integer not null, enabled integer default 1
);
create unique index if not exists uk_datasource_cluster_binding on datasource_cluster_binding(tenant_id, datasource_id, runtime_cluster_id);
create index if not exists idx_datasource_cluster_options on datasource_cluster_binding(tenant_id, runtime_cluster_id, enabled, datasource_id);

create table if not exists studio_runtime_validation (
    id integer primary key, tenant_id text default 'default', project_id integer not null,
    deleted integer default 0, created_at text, updated_at text, resource_type text not null,
    resource_id integer not null, runtime_cluster_id integer, valid integer default 1,
    issue_code text, issue_message text, details_json text, validated_at text
);
create unique index if not exists uk_runtime_validation_resource on studio_runtime_validation(tenant_id, project_id, resource_type, resource_id);
create index if not exists idx_runtime_validation_invalid on studio_runtime_validation(tenant_id, project_id, valid, resource_type);

create table if not exists datasource_connection_health (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    runtime_cluster_id integer,
    connection_fingerprint text not null,
    connection_status text default 'UNKNOWN',
    last_connection_test_at text,
    last_connection_test_message text,
    last_connection_test_duration_ms integer,
    probe_state text default 'IDLE',
    probe_owner text,
    probe_run_id text,
    probe_started_at text,
    probe_lease_until text,
    failure_count integer default 0,
    next_probe_at text
);
create unique index if not exists uk_ds_conn_health_fp on datasource_connection_health(tenant_id, runtime_cluster_id, connection_fingerprint);
create unique index if not exists uk_ds_conn_health_legacy_fp
    on datasource_connection_health(tenant_id, connection_fingerprint) where runtime_cluster_id is null;
create index if not exists idx_ds_conn_health_next on datasource_connection_health(next_probe_at);
create index if not exists idx_ds_conn_health_probe on datasource_connection_health(probe_state, probe_lease_until);

create table if not exists datasource_connection_test_record (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    runtime_cluster_id integer,
    connection_fingerprint text not null,
    datasource_id integer,
    datasource_name text,
    type_code text,
    probe_run_id text not null,
    probe_mode text,
    connection_status text default 'UNKNOWN',
    started_at text,
    ended_at text,
    duration_ms integer,
    timeout_seconds integer,
    message text
);
create unique index if not exists uk_ds_conn_record_run on datasource_connection_test_record(tenant_id, probe_run_id);
create index if not exists idx_ds_conn_record_lookup on datasource_connection_test_record(tenant_id, connection_fingerprint, ended_at);
create index if not exists idx_ds_conn_record_cluster_lookup on datasource_connection_test_record(tenant_id, runtime_cluster_id, connection_fingerprint, ended_at);
create index if not exists idx_ds_conn_record_cleanup on datasource_connection_test_record(ended_at);

create table if not exists data_model (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    datasource_id integer,
    name text,
    model_kind text,
    physical_locator text,
    schema_version_id integer,
    technical_metadata text,
    business_metadata text
);
create unique index if not exists uk_data_model_project_datasource_name on data_model(project_id, datasource_id, name);
create index if not exists idx_data_model_project on data_model(project_id);
create index if not exists idx_data_model_tenant_project_created on data_model(tenant_id, project_id, created_at);
create index if not exists idx_data_model_tenant_datasource_created on data_model(tenant_id, datasource_id, created_at);

create table if not exists model_sync_task (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    runtime_cluster_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    datasource_id integer not null,
    datasource_type text,
    datasource_name_snapshot text,
    batch_no integer not null,
    name text not null,
    source text,
    status text,
    total_count integer default 0,
    success_count integer default 0,
    failed_count integer default 0,
    stopped_count integer default 0,
    progress_percent integer default 0,
    stop_requested integer default 0,
    created_by integer,
    started_at text,
    finished_at text,
    duration_ms integer,
    last_error text
);
create unique index if not exists uk_model_sync_task_project_datasource_batch on model_sync_task(project_id, datasource_id, batch_no);
create index if not exists idx_model_sync_task_project_created on model_sync_task(project_id, created_at);
create index if not exists idx_model_sync_task_project_status on model_sync_task(project_id, status);
create index if not exists idx_model_sync_task_project_cluster_status on model_sync_task(project_id, runtime_cluster_id, status);

create table if not exists model_sync_task_item (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    task_id integer not null,
    seq_no integer not null,
    physical_locator text,
    model_name_snapshot text,
    status text,
    message text,
    started_at text,
    finished_at text,
    duration_ms integer
);
create index if not exists idx_model_sync_task_item_task_seq on model_sync_task_item(task_id, seq_no);
create index if not exists idx_model_sync_task_item_task_status on model_sync_task_item(task_id, status);

create table if not exists data_model_attr_index (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    model_id integer,
    datasource_id integer,
    meta_schema_version_id integer,
    meta_schema_code text,
    scope text,
    meta_model_code text,
    item_key text,
    field_key text,
    value_type text,
    keyword_value text,
    text_value text,
    number_value numeric,
    bool_value integer,
    raw_value text
);

create index if not exists idx_model_attr_index_project on data_model_attr_index(project_id);
create index if not exists idx_model_attr_index_model on data_model_attr_index(model_id);
create index if not exists idx_model_attr_index_tenant_model_item on data_model_attr_index(tenant_id, model_id, item_key);
create index if not exists idx_model_attr_index_datasource on data_model_attr_index(datasource_id);
create index if not exists idx_model_attr_index_lookup on data_model_attr_index(meta_schema_code, scope, field_key, keyword_value);
create index if not exists idx_model_attr_index_number on data_model_attr_index(meta_schema_code, scope, field_key, number_value);
create index if not exists idx_model_attr_index_tenant_lookup on data_model_attr_index(tenant_id, meta_schema_code, scope, field_key, keyword_value);
create index if not exists idx_model_attr_index_tenant_number on data_model_attr_index(tenant_id, meta_schema_code, scope, field_key, number_value);

create table if not exists workflow_definition (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    created_by integer,
    runtime_cluster_id integer,
    code text,
    name text,
    current_version_id integer,
    published integer default 0
);
create unique index if not exists uk_workflow_definition_project_code on workflow_definition(project_id, code);
create unique index if not exists uk_workflow_definition_project_name on workflow_definition(project_id, name);
create index if not exists idx_workflow_definition_project on workflow_definition(project_id);

create table if not exists workflow_definition_version (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    definition_id integer,
    runtime_cluster_id integer,
    version_number integer,
    published integer default 0,
    graph_json text,
    schedule_json text
);
create index if not exists idx_workflow_definition_version_project on workflow_definition_version(project_id);

create table if not exists workflow_node (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    workflow_version_id integer,
    node_code text,
    node_name text,
    node_type text,
    config_json text,
    field_mappings_json text
);
create index if not exists idx_workflow_node_project on workflow_node(project_id);

create table if not exists workflow_edge (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    workflow_version_id integer,
    from_node_code text,
    to_node_code text,
    condition_type text
);
create index if not exists idx_workflow_edge_project on workflow_edge(project_id);

create table if not exists workflow_schedule (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    workflow_definition_id integer,
    cron_expression text,
    enabled integer default 0,
    timezone text,
    last_triggered_at text
);
create index if not exists idx_workflow_schedule_project on workflow_schedule(project_id);

create table if not exists collection_task_definition (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    created_by integer,
    runtime_cluster_id integer,
    name text,
    task_type text,
    status text,
    execution_mode text not null default 'BATCH',
    source_count integer default 1,
    target_datasource_name_snapshot text,
    target_datasource_type_code_snapshot text,
    target_model_name_snapshot text,
    target_model_physical_locator_snapshot text,
    source_bindings_json text,
    target_binding_json text,
    field_mappings_json text,
    execution_options_json text,
    streaming_options_json text
);
create unique index if not exists uk_collection_task_definition_project_name on collection_task_definition(project_id, name);
create index if not exists idx_collection_task_definition_project on collection_task_definition(project_id);

create table if not exists collection_task_metric_binding (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    collection_task_id integer not null,
    task_name_snapshot text,
    task_type text,
    task_status text,
    source_count integer default 1,
    binding_role text not null,
    source_alias text,
    datasource_id integer,
    datasource_name text,
    datasource_type_code text,
    model_id integer,
    model_name text,
    model_physical_locator text
);
create index if not exists idx_collection_metric_project_task on collection_task_metric_binding(project_id, collection_task_id);
create index if not exists idx_collection_metric_role_ds on collection_task_metric_binding(binding_role, datasource_id);
create index if not exists idx_collection_metric_role_model on collection_task_metric_binding(binding_role, model_id);

create table if not exists collection_task_schedule (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    collection_task_id integer,
    cron_expression text,
    enabled integer default 0,
    timezone text,
    last_triggered_at text
);
create index if not exists idx_collection_task_schedule_project on collection_task_schedule(project_id);

create table if not exists stream_task_deploy (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    collection_task_id integer not null,
    runtime_cluster_id integer,
    generation integer not null default 0,
    desired_state text not null default 'STOPPED',
    observed_state text not null default 'STOPPED',
    current_run_id integer,
    current_attempt_id integer,
    consecutive_failure_count integer not null default 0,
    next_retry_at text,
    last_checkpoint_json text,
    last_checkpoint_at text,
    last_error_code text,
    last_error_summary text,
    version integer not null default 0
);
create unique index if not exists uk_stream_deploy_task on stream_task_deploy(tenant_id, project_id, collection_task_id);
create index if not exists idx_stream_deploy_state on stream_task_deploy(tenant_id, runtime_cluster_id, desired_state, observed_state, next_retry_at);

create table if not exists stream_task_run (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    collection_task_id integer not null,
    generation integer not null,
    runtime_cluster_id integer,
    status text not null,
    delivery_semantics text not null default 'AT_LEAST_ONCE',
    group_id text not null,
    started_by integer,
    started_at text,
    stop_requested_at text,
    stopped_by integer,
    stopped_at text,
    stop_reason text,
    final_checkpoint_json text
);
create unique index if not exists uk_stream_run_task_gen on stream_task_run(tenant_id, project_id, collection_task_id, generation);
create index if not exists idx_stream_run_task_time on stream_task_run(tenant_id, project_id, collection_task_id, started_at);

create table if not exists stream_task_attempt (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    run_id integer not null,
    collection_task_id integer not null,
    generation integer not null,
    attempt_no integer not null,
    dispatch_task_id integer,
    run_record_id integer,
    runtime_cluster_id integer,
    worker_instance_id text,
    worker_boot_id text,
    status text not null,
    started_at text,
    ended_at text,
    heartbeat_at text,
    retry_after text,
    checkpoint_json text,
    error_code text,
    error_summary text,
    committed_batch_count integer not null default 0
);
create unique index if not exists uk_stream_attempt_run_no on stream_task_attempt(tenant_id, project_id, run_id, attempt_no);
create index if not exists idx_stream_attempt_task_status on stream_task_attempt(tenant_id, project_id, collection_task_id, status, updated_at);

create table if not exists stream_metric_bucket (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    collection_task_id integer not null,
    run_id integer not null,
    attempt_id integer not null,
    bucket_start text not null,
    records_read integer not null default 0,
    write_succeed_records integer not null default 0,
    write_failed_records integer not null default 0,
    dirty_records integer not null default 0,
    bytes_read integer not null default 0,
    batch_count integer not null default 0,
    retry_count integer not null default 0,
    current_lag integer not null default 0,
    max_lag integer not null default 0,
    last_message_at text,
    last_checkpoint_at text,
    rebalance_count integer not null default 0
);
create unique index if not exists uk_stream_metric_attempt_min on stream_metric_bucket(tenant_id, project_id, attempt_id, bucket_start);
create index if not exists idx_stream_metric_task_time on stream_metric_bucket(tenant_id, project_id, collection_task_id, bucket_start);

create table if not exists stream_task_event (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    collection_task_id integer not null,
    deployment_id integer,
    run_id integer,
    attempt_id integer,
    generation integer,
    event_type text not null,
    from_state text,
    to_state text,
    message text,
    details_json text,
    actor_id integer,
    occurred_at text not null
);
create index if not exists idx_stream_event_task_time on stream_task_event(tenant_id, project_id, collection_task_id, occurred_at);
create index if not exists idx_stream_event_run_attempt on stream_task_event(run_id, attempt_id, occurred_at);

create table if not exists run_log_chunk (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    collection_task_id integer,
    run_record_id integer,
    stream_attempt_id integer,
    sequence_no integer not null,
    status text not null,
    local_path text,
    storage_type text,
    object_bucket text,
    object_key text,
    size_bytes integer not null default 0,
    checksum_sha256 text,
    chunk_started_at text,
    chunk_ended_at text,
    uploaded_at text
);
create unique index if not exists uk_run_log_chunk_attempt_seq on run_log_chunk(tenant_id, project_id, stream_attempt_id, sequence_no);
create index if not exists idx_run_log_chunk_record_seq on run_log_chunk(run_record_id, sequence_no);
create index if not exists idx_run_log_chunk_task_time on run_log_chunk(collection_task_id, chunk_started_at);

create table if not exists data_service_definition (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    created_by integer,
    runtime_cluster_id integer,
    service_code text not null,
    service_name text not null,
    service_type text not null,
    status text not null,
    source_type text not null,
    datasource_id integer,
    datasource_name_snapshot text,
    datasource_type_code text,
    model_id integer,
    model_name_snapshot text,
    model_physical_locator text,
    custom_sql text,
    request_method text,
    response_type text,
    endpoint_path text,
    service_key text,
    cache_enabled integer default 0,
    token_required integer default 1,
    default_subscription_name text,
    webservice_enabled integer default 0,
    webservice_config_json text
);
create unique index if not exists uk_data_service_project_code on data_service_definition(tenant_id, project_id, service_code);
create index if not exists idx_data_service_project_status on data_service_definition(project_id, status);
create index if not exists idx_data_service_code_key on data_service_definition(service_code, service_key);

create table if not exists data_service_request_param (
    id integer primary key,
    deleted integer default 0,
    created_at text,
    updated_at text,
    service_id integer not null,
    sort_order integer,
    param_name text not null,
    field_name text,
    value_type text,
    query_operator text,
    required integer default 0,
    description text,
    fixed_param integer default 0
);
create index if not exists idx_data_service_request_service_order on data_service_request_param(service_id, sort_order);

create table if not exists data_service_response_param (
    id integer primary key,
    deleted integer default 0,
    created_at text,
    updated_at text,
    service_id integer not null,
    sort_order integer,
    enabled integer default 1,
    param_name text not null,
    field_name text not null,
    example_value text,
    description text,
    transformers_json text
);
create index if not exists idx_data_service_response_service_order on data_service_response_param(service_id, sort_order);

create table if not exists data_service_publish_param (
    id integer primary key,
    deleted integer default 0,
    created_at text,
    updated_at text,
    service_id integer not null,
    sort_order integer,
    frontend_param_name text not null,
    backend_param_name text not null,
    position text,
    value_type text,
    example_value text,
    default_value text,
    required integer default 0,
    description text
);
create index if not exists idx_data_service_publish_service_order on data_service_publish_param(service_id, sort_order);

create table if not exists data_service_subscription (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    service_id integer not null,
    subscription_name text not null,
    token_hash text not null,
    token_masked text,
    enabled integer default 1,
    created_by integer,
    last_used_at text,
    rotated_at text,
    rotated_by integer
);
create unique index if not exists uk_data_service_sub_active_name
    on data_service_subscription(service_id, subscription_name)
    where deleted = 0;
create index if not exists idx_data_service_subscription_service_enabled on data_service_subscription(service_id, enabled);
create index if not exists idx_data_service_subscription_token on data_service_subscription(token_hash);

create table if not exists data_service_access_log (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    service_id integer,
    requested_cluster_id integer,
    actual_cluster_id integer,
    service_code_snapshot text,
    service_name_snapshot text,
    service_status_snapshot text,
    subscription_id integer,
    subscription_name_snapshot text,
    request_id text,
    request_method text,
    occurred_at text,
    duration_ms integer,
    success integer default 0,
    http_status integer,
    error_code text,
    error_message text,
    system_log text,
    client_ip text,
    user_agent text,
    cache_enabled integer default 0,
    cache_hit integer default 0,
    row_count integer default 0,
    log_storage_type text,
    log_object_bucket text,
    log_object_key text,
    log_size_bytes integer,
    log_charset text,
    log_archive_status text,
    log_archive_error text
);
create index if not exists idx_data_service_access_project_time on data_service_access_log(tenant_id, project_id, occurred_at);
create index if not exists idx_data_service_access_service_time on data_service_access_log(service_id, occurred_at);
create index if not exists idx_data_service_access_subscription_time on data_service_access_log(subscription_id, occurred_at);
create index if not exists idx_data_service_access_success on data_service_access_log(project_id, success, occurred_at);
create index if not exists idx_data_service_access_cache on data_service_access_log(project_id, cache_hit, occurred_at);

create table if not exists data_service_access_counter (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    service_id integer not null default 0,
    subscription_id integer not null default 0,
    bucket_start text not null,
    success integer not null default 0,
    cache_enabled integer not null default 0,
    cache_hit integer not null default 0,
    access_count integer default 0,
    row_count integer default 0
);
create unique index if not exists uk_data_service_access_counter on data_service_access_counter(tenant_id, project_id, service_id, subscription_id, bucket_start, success, cache_enabled, cache_hit);
create index if not exists idx_data_service_counter_project_bucket on data_service_access_counter(tenant_id, project_id, bucket_start);
create index if not exists idx_data_service_counter_service_bucket on data_service_access_counter(service_id, bucket_start);

create table if not exists data_ingestion_service (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    created_by integer,
    runtime_cluster_id integer,
    service_code text not null,
    service_name text not null,
    status text not null,
    request_format text not null,
    payload_mode text,
    data_node_path text,
    target_type text not null,
    datasource_id integer,
    datasource_name_snapshot text,
    datasource_type_code text,
    model_id integer,
    model_name_snapshot text,
    model_physical_locator text,
    endpoint_path text,
    service_key text,
    max_batch_size integer default 500,
    token_required integer default 1,
    default_subscription_name text,
    webservice_enabled integer default 0,
    webservice_config_json text,
    writer_options_json text,
    field_mappings_json text,
    source_positions_json text,
    source_bindings_json text,
    source_count integer default 1,
    target_count integer default 1
);

create unique index if not exists uk_data_ingestion_project_code
    on data_ingestion_service(tenant_id, project_id, service_code);
create index if not exists idx_data_ingestion_project_status
    on data_ingestion_service(project_id, status);
create index if not exists idx_data_ingestion_code_key
    on data_ingestion_service(service_code, service_key);

create table if not exists data_ingestion_subscription (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    service_id integer not null,
    subscription_name text not null,
    token_hash text not null,
    token_masked text,
    enabled integer default 1,
    created_by integer,
    last_used_at text,
    rotated_at text,
    rotated_by integer
);

create index if not exists idx_data_ingestion_sub_service_enabled
    on data_ingestion_subscription(service_id, enabled);
create unique index if not exists uk_data_ingestion_sub_active_name
    on data_ingestion_subscription(service_id, subscription_name) where enabled = 1;
create index if not exists idx_data_ingestion_sub_token
    on data_ingestion_subscription(token_hash);

create table if not exists data_ingestion_access_log (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    service_id integer,
    requested_cluster_id integer,
    actual_cluster_id integer,
    service_code_snapshot text,
    service_name_snapshot text,
    service_status_snapshot text,
    subscription_id integer,
    subscription_name_snapshot text,
    request_id text,
    request_method text,
    occurred_at text,
    duration_ms integer,
    success integer default 0,
    http_status integer,
    error_code text,
    error_message text,
    system_log text,
    client_ip text,
    user_agent text,
    received_count integer default 0,
    success_count integer default 0,
    failed_count integer default 0,
    log_storage_type text,
    log_object_bucket text,
    log_object_key text,
    log_size_bytes integer,
    log_charset text,
    log_archive_status text,
    log_archive_error text
);

create index if not exists idx_data_ingestion_access_project_time
    on data_ingestion_access_log(tenant_id, project_id, occurred_at);
create index if not exists idx_data_ingestion_access_service_time
    on data_ingestion_access_log(service_id, occurred_at);
create index if not exists idx_data_ingestion_access_subscription_time
    on data_ingestion_access_log(subscription_id, occurred_at);
create index if not exists idx_data_ingestion_access_success
    on data_ingestion_access_log(project_id, success, occurred_at);

create table if not exists data_ingestion_access_counter (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    service_id integer not null default 0,
    subscription_id integer not null default 0,
    bucket_start text not null,
    success integer not null default 0,
    access_count integer default 0,
    received_count integer default 0,
    success_count integer default 0,
    failed_count integer default 0
);

create unique index if not exists uk_data_ingestion_access_counter
    on data_ingestion_access_counter(tenant_id, project_id, service_id, subscription_id, bucket_start, success);
create index if not exists idx_data_ingestion_counter_project_bucket
    on data_ingestion_access_counter(tenant_id, project_id, bucket_start);
create index if not exists idx_data_ingestion_counter_service_bucket
    on data_ingestion_access_counter(service_id, bucket_start);

create table if not exists protocol_conversion_service (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    created_by integer,
    runtime_cluster_id integer,
    service_code text not null,
    service_name text not null,
    status text not null,
    endpoint_path text,
    webservice_endpoint_path text,
    service_key text,
    token_required integer default 1,
    default_subscription_name text,
    source_protocol text not null,
    source_method text,
    source_data_node_path text,
    webservice_config_json text,
    conversion_mode text not null,
    field_mappings_json text,
    raw_transformers_json text,
    fixed_fields_json text,
    body_bridge_options_json text,
    request_passthrough_json text,
    target_datasource_id integer,
    target_datasource_name_snapshot text,
    target_path text,
    target_protocol text not null,
    target_method text,
    target_headers_json text,
    target_query_json text,
    target_webservice_config_json text,
    target_body_template text,
    target_data_node_path text,
    payload_mode text,
    batch_size integer default 1,
    response_status_json text
);

create unique index if not exists uk_protocol_conversion_project_code
    on protocol_conversion_service(tenant_id, project_id, service_code);
create index if not exists idx_protocol_conversion_project_status
    on protocol_conversion_service(project_id, status);
create index if not exists idx_protocol_conversion_code_key
    on protocol_conversion_service(service_code, service_key);

create table if not exists protocol_conversion_subscription (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    service_id integer not null,
    subscription_name text not null,
    token_hash text not null,
    token_masked text,
    enabled integer default 1,
    created_by integer,
    last_used_at text,
    rotated_at text,
    rotated_by integer
);

create index if not exists idx_protocol_conversion_sub_service_enabled
    on protocol_conversion_subscription(service_id, enabled);
create unique index if not exists uk_protocol_conversion_sub_active_name
    on protocol_conversion_subscription(service_id, subscription_name) where enabled = 1;
create index if not exists idx_protocol_conversion_sub_token
    on protocol_conversion_subscription(token_hash);

create table if not exists protocol_conversion_access_log (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    service_id integer,
    requested_cluster_id integer,
    actual_cluster_id integer,
    service_code_snapshot text,
    service_name_snapshot text,
    service_status_snapshot text,
    subscription_id integer,
    subscription_name_snapshot text,
    request_id text,
    request_method text,
    source_protocol_snapshot text,
    target_protocol_snapshot text,
    occurred_at text,
    duration_ms integer,
    success integer default 0,
    http_status integer,
    target_http_status integer,
    error_code text,
    error_message text,
    system_log text,
    client_ip text,
    user_agent text,
    received_count integer default 0,
    success_count integer default 0,
    failed_count integer default 0,
    log_storage_type text,
    log_object_bucket text,
    log_object_key text,
    log_size_bytes integer,
    log_charset text,
    log_archive_status text,
    log_archive_error text
);

create index if not exists idx_protocol_conversion_access_project_time
    on protocol_conversion_access_log(tenant_id, project_id, occurred_at);
create index if not exists idx_protocol_conversion_access_service_time
    on protocol_conversion_access_log(service_id, occurred_at);
create index if not exists idx_protocol_conversion_access_subscription_time
    on protocol_conversion_access_log(subscription_id, occurred_at);
create index if not exists idx_protocol_conversion_access_success
    on protocol_conversion_access_log(project_id, success, occurred_at);

create table if not exists protocol_conversion_access_counter (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    service_id integer not null default 0,
    subscription_id integer not null default 0,
    bucket_start text not null,
    success integer not null default 0,
    access_count integer default 0,
    received_count integer default 0,
    success_count integer default 0,
    failed_count integer default 0
);

create unique index if not exists uk_protocol_conversion_access_counter
    on protocol_conversion_access_counter(tenant_id, project_id, service_id, subscription_id, bucket_start, success);
create index if not exists idx_protocol_conversion_counter_project_bucket
    on protocol_conversion_access_counter(tenant_id, project_id, bucket_start);
create index if not exists idx_protocol_conversion_counter_service_bucket
    on protocol_conversion_access_counter(service_id, bucket_start);

create table if not exists data_dev_directory (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    parent_id integer,
    name text,
    permission_code text,
    description text
);

create table if not exists data_dev_script (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    directory_id integer,
    runtime_cluster_id integer,
    file_name text,
    script_type text,
    datasource_id integer,
    environment_id integer,
    description text,
    execution_config_json text,
    content text
);

create index if not exists idx_data_dev_directory_project_parent on data_dev_directory(project_id, parent_id);
create index if not exists idx_data_dev_directory_parent on data_dev_directory(parent_id);
create index if not exists idx_data_dev_script_project_directory on data_dev_script(project_id, directory_id);
create index if not exists idx_data_dev_script_directory on data_dev_script(directory_id);
create index if not exists idx_data_dev_script_datasource on data_dev_script(datasource_id);
create index if not exists idx_data_dev_script_environment on data_dev_script(environment_id);

create table if not exists so_pf_env_dep (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    name text not null,
    version text,
    script_type text default 'JAVA',
    artifact_url text,
    artifact_type text,
    checksum text,
    enabled integer default 1,
    description text
);
create index if not exists idx_so_pf_env_dep_tenant_enabled on so_pf_env_dep(tenant_id, enabled);
create unique index if not exists uk_so_pf_env_dep_name_ver on so_pf_env_dep(tenant_id, name, version);

create table if not exists so_pf_env_dep_file (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    dependency_id integer not null,
    original_file_name text not null,
    artifact_type text not null,
    object_key text,
    object_url text,
    checksum text,
    size_bytes integer,
    visible integer default 1,
    runtime_artifact integer default 0,
    source_file_id integer,
    enabled integer default 1
);
create index if not exists idx_so_pf_env_dep_file_dep on so_pf_env_dep_file(tenant_id, dependency_id, visible);
create index if not exists idx_so_pf_env_dep_file_runtime on so_pf_env_dep_file(tenant_id, dependency_id, runtime_artifact);

create table if not exists so_pf_script_env (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    environment_name text not null,
    environment_code text not null,
    enabled integer default 1,
    use_application_parent integer default 1,
    environment_version integer default 1,
    python_install_mode text default 'LOCAL_ARTIFACT',
    python_repository_id integer,
    description text
);
create unique index if not exists uk_so_pf_script_env_code on so_pf_script_env(tenant_id, environment_code);
create index if not exists idx_so_pf_script_env_enabled on so_pf_script_env(tenant_id, enabled);

create table if not exists so_pf_env_dep_rel (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    environment_id integer not null,
    dependency_id integer not null,
    sort_order integer default 0
);
create index if not exists idx_so_pf_env_dep_rel_env on so_pf_env_dep_rel(environment_id, sort_order);
create unique index if not exists uk_so_pf_env_dep_rel on so_pf_env_dep_rel(environment_id, dependency_id);

create table if not exists dispatch_task (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    execution_type text,
    workflow_run_id integer,
    workflow_definition_id integer,
    workflow_version_id integer,
    collection_task_id integer,
    quality_task_id integer,
    file_transfer_task_id integer,
    file_transfer_run_id integer,
    triggered_by_user_id integer,
    run_record_id integer,
    node_code text,
    status text,
    termination_requested integer default 0,
    target_cluster_id integer,
    resource_revision text,
    claim_token text,
    worker_boot_id text,
    worker_group_code text,
    lease_owner text,
    worker_instance_id text,
    lease_expires_at text,
    scheduled_fire_time text,
    attempts integer default 0,
    max_retries integer default 3,
    protected_payload_ciphertext text,
    payload_json text
);
create index if not exists idx_dispatch_task_project_status on dispatch_task(project_id, status);
create index if not exists idx_dispatch_task_project_workflow_run on dispatch_task(project_id, workflow_run_id);
create index if not exists idx_dispatch_task_project_quality_task_status on dispatch_task(project_id, quality_task_id, status);
create index if not exists idx_dispatch_task_file_transfer_run on dispatch_task(file_transfer_run_id, status);
create index if not exists idx_dispatch_task_project_status_created on dispatch_task(project_id, status, created_at);
create index if not exists idx_dispatch_task_group_status_created on dispatch_task(worker_group_code, status, created_at);
create index if not exists idx_dispatch_task_cluster_status_created on dispatch_task(target_cluster_id, status, created_at);
create index if not exists idx_dispatch_task_termination_status on dispatch_task(termination_requested, status, worker_instance_id);

create table if not exists run_record (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    execution_type text,
    workflow_run_id integer,
    workflow_definition_id integer,
    workflow_version_id integer,
    collection_task_id integer,
    quality_task_id integer,
    file_transfer_task_id integer,
    file_transfer_run_id integer,
    triggered_by_user_id integer,
    node_code text,
    status text,
    termination_requested integer default 0,
    requested_cluster_id integer,
    actual_cluster_id integer,
    actual_cluster_code text,
    worker_group_code text,
    worker_code text,
    worker_instance_id text,
    worker_boot_id text,
    worker_pod_name text,
    worker_node_name text,
    message text,
    started_at text,
    ended_at text,
    collected_records integer,
    read_succeed_records integer,
    read_failed_records integer,
    write_succeed_records integer,
    write_failed_records integer,
    failed_records integer,
    success_records integer,
    transformer_total_records integer,
    transformer_success_records integer,
    transformer_failed_records integer,
    transformer_filter_records integer,
    log_file_path text,
    log_size_bytes integer,
    log_charset text,
    log_storage_type text,
    log_object_bucket text,
    log_object_key text,
    log_chunk_count integer,
    log_status text,
    log_error_summary text,
    payload_json text,
    result_json text
);
create index if not exists idx_run_record_project_created on run_record(project_id, created_at);
create index if not exists idx_run_record_project_workflow_run on run_record(project_id, workflow_run_id);
create index if not exists idx_run_record_project_collection_task_ended on run_record(project_id, collection_task_id, ended_at);
create index if not exists idx_run_record_project_quality_task_ended on run_record(project_id, quality_task_id, ended_at);
create index if not exists idx_run_record_file_transfer_run on run_record(file_transfer_run_id);
create index if not exists idx_run_record_project_cluster_created on run_record(project_id, requested_cluster_id, created_at);
create index if not exists idx_run_record_termination_status on run_record(termination_requested, status, worker_instance_id);

create table if not exists file_transfer_task_definition (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    created_by integer,
    name text not null,
    code text not null,
    status text not null,
    version integer not null default 1,
    published_version integer,
    runtime_cluster_id integer,
    source_runtime_cluster_id integer not null,
    source_datasource_id integer not null,
    source_datasource_name_snapshot text,
    source_datasource_type_snapshot text,
    target_runtime_cluster_id integer not null,
    target_datasource_id integer not null,
    target_datasource_name_snapshot text,
    target_datasource_type_snapshot text,
    selection_json text,
    mapping_json text,
    policy_json text,
    runtime_json text,
    published_snapshot_json text,
    schedule_enabled integer default 0,
    cron_expression text,
    timezone text,
    last_triggered_at text
);
create unique index if not exists uk_ft_task_project_code on file_transfer_task_definition(tenant_id, project_id, code) where deleted = 0;
create index if not exists idx_ft_task_project_status on file_transfer_task_definition(project_id, status);
create index if not exists idx_ft_task_schedule on file_transfer_task_definition(status, schedule_enabled, last_triggered_at);

create table if not exists file_transfer_run (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    run_record_id integer,
    task_id integer,
    task_name_snapshot text,
    trigger_type text,
    direction text,
    channel text,
    status text not null,
    queue_visible integer not null default 1,
    runtime_cluster_id integer,
    source_runtime_cluster_id integer,
    source_datasource_id integer,
    target_runtime_cluster_id integer not null,
    target_datasource_id integer,
    total_files integer default 0,
    success_files integer default 0,
    skipped_files integer default 0,
    failed_files integer default 0,
    conflict_files integer default 0,
    resumed_files integer default 0,
    post_action_failed_files integer default 0,
    total_bytes integer default 0,
    transferred_bytes integer default 0,
    failed_bytes integer default 0,
    resumed_bytes integer default 0,
    current_bytes_per_second integer default 0,
    peak_bytes_per_second integer default 0,
    active_files integer default 0,
    retry_count integer default 0,
    message text,
    started_at text,
    ended_at text,
    resolved_spec_json text
);
create unique index if not exists uk_ft_run_record on file_transfer_run(run_record_id) where run_record_id is not null;
create index if not exists idx_ft_run_project_created on file_transfer_run(project_id, created_at);
create index if not exists idx_ft_run_task_status on file_transfer_run(task_id, status);
create index if not exists idx_ft_run_target_status on file_transfer_run(target_runtime_cluster_id, status);

create table if not exists file_transfer_run_item (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    run_id integer not null,
    core_item_id text not null,
    direction text,
    channel text,
    runtime_cluster_id integer,
    source_runtime_cluster_id integer not null,
    source_datasource_id integer not null,
    source_path text not null,
    target_runtime_cluster_id integer not null,
    target_datasource_id integer not null,
    target_path text not null,
    temporary_path text,
    status text not null,
    file_size integer default 0,
    transferred_bytes integer default 0,
    resumed_bytes integer default 0,
    current_bytes_per_second integer default 0,
    source_checksum text,
    target_checksum text,
    attempts integer default 0,
    error_code text,
    error_message text,
    conflict_action text,
    source_action text,
    post_action_status text,
    started_at text,
    ended_at text,
    source_snapshot_json text,
    checkpoint_json text
);
create unique index if not exists uk_ft_item_run_core on file_transfer_run_item(run_id, core_item_id);
create index if not exists idx_ft_item_run_status on file_transfer_run_item(run_id, status);
create index if not exists idx_ft_item_project_updated on file_transfer_run_item(project_id, updated_at);

create table if not exists file_transfer_metric_sample (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    run_id integer not null,
    run_record_id integer,
    task_id integer,
    runtime_cluster_id integer,
    source_datasource_id integer,
    target_datasource_id integer,
    channel text,
    status text,
    sampled_at text not null,
    transferred_bytes integer default 0,
    bytes_per_second integer default 0,
    completed_files integer default 0,
    failed_files integer default 0,
    active_files integer default 0,
    retry_count integer default 0
);
create index if not exists idx_ft_metric_project_time on file_transfer_metric_sample(project_id, sampled_at);
create index if not exists idx_ft_metric_run_time on file_transfer_metric_sample(run_id, sampled_at);
create index if not exists idx_ft_metric_task_time on file_transfer_metric_sample(task_id, sampled_at);

create table if not exists file_transfer_event_outbox (
    id integer primary key,
    tenant_id text not null,
    project_id integer not null,
    deleted integer not null default 0,
    created_at text not null,
    updated_at text not null,
    event_type text not null,
    run_id integer not null,
    item_id integer,
    occurred_at text not null,
    payload_version integer not null default 1,
    payload_json text
);
create index if not exists idx_ft_outbox_scope_id on file_transfer_event_outbox(tenant_id, project_id, id);
create index if not exists idx_ft_outbox_run_id on file_transfer_event_outbox(run_id, id);
create index if not exists idx_ft_outbox_created on file_transfer_event_outbox(created_at, id);
create index if not exists idx_ft_outbox_event_type on file_transfer_event_outbox(event_type, created_at, id);

create table if not exists file_transfer_event_consumer_cursor (
    id integer primary key,
    instance_id text not null,
    tenant_id text not null,
    project_id integer not null,
    last_event_id integer not null default 0,
    last_seen_at text not null,
    created_at text not null,
    updated_at text not null
);
create unique index if not exists uk_ft_event_cursor_scope on file_transfer_event_consumer_cursor(instance_id, tenant_id, project_id);
create index if not exists idx_ft_event_cursor_seen on file_transfer_event_consumer_cursor(instance_id, last_seen_at);
create index if not exists idx_ft_event_cursor_position on file_transfer_event_consumer_cursor(tenant_id, project_id, last_event_id);

create table if not exists data_model_lineage_relation (
    id integer primary key,
    tenant_id text default 'default',
    project_id integer,
    deleted integer default 0,
    created_at text,
    updated_at text,
    level text,
    source_type text,
    collection_task_id integer,
    collection_task_name_snapshot text,
    source_datasource_id integer,
    source_datasource_name_snapshot text,
    source_datasource_type_snapshot text,
    source_database_name_snapshot text,
    source_host_snapshot text,
    source_port_snapshot text,
    source_model_id integer,
    source_model_name_snapshot text,
    source_model_locator_snapshot text,
    source_field_key text,
    target_datasource_id integer,
    target_datasource_name_snapshot text,
    target_datasource_type_snapshot text,
    target_database_name_snapshot text,
    target_host_snapshot text,
    target_port_snapshot text,
    target_model_id integer,
    target_model_name_snapshot text,
    target_model_locator_snapshot text,
    target_field_key text,
    mapping_mode text,
    expression_snapshot text,
    manual_maintainer_user_id integer,
    manual_maintainer_name_snapshot text,
    latest_run_id integer,
    latest_run_status text,
    latest_run_at text
);
create index if not exists idx_data_model_lineage_target_level on data_model_lineage_relation(tenant_id, target_model_id, level);
create index if not exists idx_data_model_lineage_source_level on data_model_lineage_relation(tenant_id, source_model_id, level);
create index if not exists idx_data_model_lineage_task on data_model_lineage_relation(tenant_id, collection_task_id);
create index if not exists idx_data_model_lineage_target_datasource_level on data_model_lineage_relation(tenant_id, target_datasource_id, level);
create index if not exists idx_data_model_lineage_source_datasource_level on data_model_lineage_relation(tenant_id, source_datasource_id, level);

create table if not exists worker_lease (
    id integer primary key,
    tenant_id text default 'default',
    deleted integer default 0,
    created_at text,
    updated_at text,
    runtime_cluster_id integer,
    runtime_cluster_code text,
    worker_group_code text,
    worker_code text,
    worker_kind text,
    instance_id text,
    boot_id text,
    runtime_version text,
    plugin_fingerprint text,
    host_name text,
    pod_name text,
    node_name text,
    status text,
    last_heartbeat_at text,
    lease_expires_at text,
    capabilities_json text
);
create index if not exists idx_worker_lease_code_instance on worker_lease(worker_code, instance_id);
create index if not exists idx_worker_lease_group_instance on worker_lease(worker_group_code, instance_id);
create index if not exists idx_worker_lease_status_heartbeat on worker_lease(status, last_heartbeat_at);
create index if not exists idx_worker_lease_cluster_status on worker_lease(runtime_cluster_id, status, last_heartbeat_at);
