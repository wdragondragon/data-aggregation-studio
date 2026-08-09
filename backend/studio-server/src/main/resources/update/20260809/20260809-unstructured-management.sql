create table if not exists unstructured_source_acl (
    id bigint primary key,
    tenant_id varchar(64) default 'default', project_id bigint not null, deleted int default 0,
    created_at datetime default current_timestamp, updated_at datetime default current_timestamp,
    datasource_id bigint not null, principal_type varchar(32) not null, user_id bigint,
    permission varchar(32) not null, effect varchar(32) not null, created_by bigint,
    unique key uk_unstructured_source_acl (tenant_id, datasource_id, principal_type, user_id, permission)
);
set @ddl = (
    select if(count(*) = 0,
        'alter table unstructured_source_acl add key idx_unstructured_source_acl_source (tenant_id, project_id, datasource_id)',
        'select 1')
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'unstructured_source_acl'
      and index_name = 'idx_unstructured_source_acl_source'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

create table if not exists unstructured_path_acl (
    id bigint primary key,
    tenant_id varchar(64) default 'default', project_id bigint not null, deleted int default 0,
    created_at datetime default current_timestamp, updated_at datetime default current_timestamp,
    datasource_id bigint not null, path varchar(2000) not null, directory int default 1, principal_type varchar(32) not null,
    user_id bigint, permission varchar(32) not null, effect varchar(32) not null, created_by bigint,
    unique key uk_unstructured_path_acl (tenant_id, datasource_id, path(255), principal_type, user_id, permission)
);
set @ddl = (
    select if(count(*) = 0,
        'alter table unstructured_path_acl add column directory int default 1 after path',
        'select 1')
    from information_schema.columns
    where table_schema = database()
      and table_name = 'unstructured_path_acl'
      and column_name = 'directory'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table unstructured_path_acl add key idx_unstructured_path_acl_source (tenant_id, project_id, datasource_id, path(255))',
        'select 1')
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'unstructured_path_acl'
      and index_name = 'idx_unstructured_path_acl_source'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

create table if not exists unstructured_op_audit (
    id bigint primary key,
    tenant_id varchar(64) default 'default', project_id bigint not null, deleted int default 0,
    created_at datetime default current_timestamp, updated_at datetime default current_timestamp,
    datasource_id bigint not null, runtime_cluster_id bigint not null, user_id bigint,
    username varchar(255), operation varchar(32) not null, source_path varchar(2000), target_path varchar(2000),
    `recursive` int default 0, status varchar(32) not null, message varchar(2000),
    key idx_unstructured_op_audit_source (tenant_id, project_id, datasource_id, created_at)
);
