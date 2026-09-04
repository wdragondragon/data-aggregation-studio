alter table meta_field_definition
    add column file_policy_code varchar(64) null after component_type;

create table if not exists so_pf_managed_file (
    id bigint primary key,
    tenant_id varchar(64) not null default 'default',
    project_id bigint not null,
    deleted int not null default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    original_file_name varchar(255) not null,
    policy_code varchar(64) not null,
    content_type varchar(255),
    plaintext_size bigint,
    ciphertext_size bigint,
    sha256 varchar(64),
    object_bucket varchar(255),
    object_key varchar(1000),
    encryption_algorithm varchar(64),
    encryption_version int,
    encryption_iv varchar(128),
    status varchar(32) not null,
    expires_at datetime,
    bound_at datetime,
    last_referenced_at datetime,
    uploaded_by bigint,
    error_message varchar(1000),
    delete_retry_count int not null default 0,
    next_delete_attempt_at datetime,
    deleted_at datetime,
    key idx_mfile_project_status (tenant_id, project_id, status, created_at),
    key idx_mfile_gc (status, expires_at, next_delete_attempt_at),
    key idx_mfile_sha (tenant_id, project_id, sha256)
);

create table if not exists so_pf_managed_file_ref (
    id bigint primary key,
    tenant_id varchar(64) not null default 'default',
    project_id bigint not null,
    deleted int not null default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    file_id bigint not null,
    owner_type varchar(64) not null,
    owner_id bigint not null,
    field_key varchar(255) not null,
    ordinal int not null default 0,
    unique key uk_mfile_ref_owner (tenant_id, project_id, owner_type, owner_id, field_key, ordinal),
    key idx_mfile_ref_file (file_id),
    key idx_mfile_ref_owner (tenant_id, project_id, owner_type, owner_id)
);

create table if not exists so_pf_managed_file_lease (
    id bigint primary key,
    tenant_id varchar(64) not null default 'default',
    project_id bigint not null,
    deleted int not null default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    file_id bigint not null,
    lease_token varchar(64) not null,
    consumer_type varchar(64),
    consumer_id varchar(255),
    worker_instance_id varchar(255),
    heartbeat_at datetime,
    expires_at datetime not null,
    released_at datetime,
    unique key uk_mfile_lease_token (lease_token),
    key idx_mfile_lease_active (file_id, released_at, expires_at)
);

create table if not exists so_pf_managed_file_audit (
    id bigint primary key,
    tenant_id varchar(64) not null default 'default',
    project_id bigint not null,
    deleted int not null default 0,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    file_id bigint,
    action varchar(64) not null,
    outcome varchar(32) not null,
    actor_user_id bigint,
    actor_name varchar(255),
    owner_type varchar(64),
    owner_id bigint,
    field_key varchar(255),
    detail varchar(1000),
    key idx_mfile_audit_file (tenant_id, project_id, file_id, created_at),
    key idx_mfile_audit_time (created_at)
);

update meta_field_definition
set component_type = 'MANAGED_FILE', file_policy_code = 'KERBEROS_KEYTAB',
    field_name = 'Kerberos Keytab', description = '上传或选择托管的 Kerberos Keytab 文件'
where field_key in ('kerberosKeytabFilePath', 'keytabPath')
  and schema_version_id in (
      select current_version_id from meta_schema
      where object_type = 'datasource' and type_code in ('kafka', 'tbds-hdfs', 'tbds-hdfs3', 'tbds-hive3')
  );

update meta_field_definition
set component_type = 'MANAGED_FILE', file_policy_code = 'KERBEROS_KRB5_CONF',
    field_name = 'Kerberos krb5.conf', description = '上传或选择托管的自包含 krb5.conf 文件'
where field_key in ('krb5Conf', 'krb5File')
  and schema_version_id in (
      select current_version_id from meta_schema
      where object_type = 'datasource' and type_code in ('kafka', 'tbds-hdfs', 'tbds-hdfs3', 'tbds-hive3')
  );

update meta_field_definition
set component_type = 'MANAGED_FILE', file_policy_code = 'HADOOP_SITE_XML',
    field_name = case field_key when 'hdfsSiteFilePath' then 'hdfs-site.xml' else 'core-site.xml' end,
    description = '上传或选择托管的 Hadoop site XML 文件'
where field_key in ('hdfsSiteFilePath', 'coreSiteFilePath')
  and schema_version_id in (
      select current_version_id from meta_schema
      where object_type = 'datasource' and type_code in ('tbds-hdfs', 'tbds-hdfs3')
  );
