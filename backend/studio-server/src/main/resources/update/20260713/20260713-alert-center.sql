-- P0-01 unified alert center (MySQL)
-- Additive migration. No default rules or channels are created.

create table if not exists studio_alert_rule (
    id bigint primary key, tenant_id varchar(64) default 'default', project_id bigint,
    deleted int default 0, created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp, name varchar(255) not null,
    active_name varchar(255) generated always as (case when deleted = 0 then name else null end) stored,
    description text, rule_type varchar(64) not null, subject_type varchar(64) not null,
    subject_id bigint, subject_name_snapshot varchar(255), severity varchar(32) not null,
    enabled int default 0, condition_json json, silence_minutes int default 30,
    recovery_notification_enabled int default 1, in_app_enabled int default 1,
    recipient_user_ids_json json, notify_resource_owner int default 0,
    notify_project_admins int default 1, webhook_channel_ids_json json,
    activation_at datetime, last_evaluated_at datetime, last_evaluation_status varchar(32),
    last_evaluation_error varchar(1000), last_triggered_at datetime,
    created_by bigint, updated_by bigint,
    unique key uk_alert_rule_active_name (tenant_id, project_id, active_name),
    key idx_alert_rule_project_enabled (project_id, enabled, rule_type),
    key idx_alert_rule_project_name (project_id, name)
);

create table if not exists studio_alert_incident (
    id bigint primary key, tenant_id varchar(64) default 'default', project_id bigint,
    deleted int default 0, created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp, rule_id bigint not null,
    rule_name_snapshot varchar(255), rule_type varchar(64), signature varchar(64) not null,
    subject_type varchar(64), subject_key varchar(255), subject_id bigint,
    subject_name_snapshot varchar(255), target_path varchar(1000), severity varchar(32),
    status varchar(32), summary varchar(1000), current_evidence_json json,
    occurrence_count int default 0, notification_count int default 0,
    reopen_count int default 0, condition_active int default 0,
    closed_while_active int default 0, first_triggered_at datetime,
    last_triggered_at datetime, last_notified_at datetime, acknowledged_at datetime,
    recovered_at datetime, closed_at datetime, acknowledged_by bigint, closed_by bigint,
    version int default 0,
    unique key uk_alert_incident_signature (tenant_id, project_id, signature),
    key idx_alert_incident_status (project_id, status, severity, last_triggered_at),
    key idx_alert_incident_rule (rule_id, last_triggered_at),
    key idx_alert_incident_subject (project_id, subject_type, subject_id)
);

create table if not exists studio_alert_event (
    id bigint primary key, tenant_id varchar(64) default 'default', project_id bigint,
    deleted int default 0, created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp, incident_id bigint, rule_id bigint,
    event_type varchar(32) not null, status_from varchar(32), status_to varchar(32),
    source_type varchar(64), source_id varchar(255), source_event_key varchar(255) not null,
    subject_type varchar(64), subject_key varchar(255), subject_id bigint,
    subject_name_snapshot varchar(255), target_path varchar(1000), severity varchar(32),
    summary varchar(1000), evidence_json json, actor_user_id bigint,
    actor_name_snapshot varchar(255), observed_at datetime,
    unique key uk_alert_event_source (tenant_id, project_id, source_event_key),
    key idx_alert_event_incident (incident_id, observed_at),
    key idx_alert_event_rule (rule_id, event_type, observed_at)
);

create table if not exists studio_alert_channel (
    id bigint primary key, tenant_id varchar(64) default 'default', project_id bigint,
    deleted int default 0, created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp, name varchar(255) not null,
    active_name varchar(255) generated always as (case when deleted = 0 then name else null end) stored,
    channel_type varchar(32) not null, endpoint_ciphertext text, headers_ciphertext text,
    signing_secret_ciphertext text, enabled int default 1, last_tested_at datetime,
    last_test_status varchar(32), last_test_message varchar(1000),
    created_by bigint, updated_by bigint,
    unique key uk_alert_channel_active_name (tenant_id, project_id, active_name),
    key idx_alert_channel_project_enabled (project_id, enabled),
    key idx_alert_channel_project_name (project_id, name)
);

create table if not exists studio_alert_delivery (
    id bigint primary key, tenant_id varchar(64) default 'default', project_id bigint,
    deleted int default 0, created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp, event_id bigint not null,
    incident_id bigint, delivery_key varchar(255) not null,
    channel_type varchar(32) not null, channel_id bigint,
    channel_name_snapshot varchar(255), recipient_user_id bigint,
    status varchar(32) not null, attempt_count int default 0,
    next_attempt_at datetime, last_attempt_at datetime, http_status int,
    response_excerpt varchar(2000), error_message varchar(1000), payload_json json,
    unique key uk_alert_delivery_event_key (event_id, delivery_key),
    key idx_alert_delivery_due (status, next_attempt_at),
    key idx_alert_delivery_channel (channel_id, created_at),
    key idx_alert_delivery_incident (incident_id, created_at)
);
