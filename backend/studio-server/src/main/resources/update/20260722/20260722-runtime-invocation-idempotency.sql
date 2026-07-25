-- P0-MC-02: shared-database idempotency guard for public ingestion/conversion writes.
--
-- The public Idempotency-Key, request body, authentication token and business
-- header values are never persisted. key_hash/request_fingerprint are SHA-256;
-- the replay payload is encrypted by Studio before it is stored.

create table if not exists studio_runtime_idempotency (
    id bigint primary key,
    tenant_id varchar(64) default 'default',
    project_id bigint not null,
    deleted int default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    runtime_cluster_id bigint,
    resource_type varchar(64) not null,
    resource_id bigint not null,
    key_hash char(64) not null,
    request_fingerprint char(64) not null,
    status varchar(16) not null,
    owner_token_hash char(64) not null,
    owner_instance_id varchar(128) not null,
    owner_boot_id varchar(128) not null,
    response_status int,
    response_content_type varchar(512),
    response_body_ciphertext longtext,
    completed_at datetime,
    version int default 0,
    unique key uk_runtime_idem_scope_key (tenant_id, project_id, resource_type, resource_id, key_hash),
    key idx_runtime_idem_status_updated (status, updated_at)
);
