-- Data Aggregation Studio increment schema script
-- Target: K8s HA dispatch locks, worker instance leases, and object-storage run logs.

set @ddl = (
    select if(count(*) = 0, 'alter table dispatch_task add column worker_instance_id varchar(255)', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'dispatch_task' and column_name = 'worker_instance_id'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table dispatch_task add column scheduled_fire_time datetime', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'dispatch_task' and column_name = 'scheduled_fire_time'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table run_record add column worker_instance_id varchar(255)', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'run_record' and column_name = 'worker_instance_id'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table run_record add column log_storage_type varchar(64)', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'run_record' and column_name = 'log_storage_type'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table run_record add column log_object_bucket varchar(255)', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'run_record' and column_name = 'log_object_bucket'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table run_record add column log_object_key varchar(1000)', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'run_record' and column_name = 'log_object_key'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table run_record add column log_chunk_count int', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'run_record' and column_name = 'log_chunk_count'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table run_record add column log_status varchar(64)', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'run_record' and column_name = 'log_status'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table run_record add column log_error_summary varchar(1000)', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'run_record' and column_name = 'log_error_summary'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table worker_lease add column instance_id varchar(255)', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'worker_lease' and column_name = 'instance_id'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table worker_lease add column pod_name varchar(255)', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'worker_lease' and column_name = 'pod_name'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table worker_lease add column node_name varchar(255)', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'worker_lease' and column_name = 'node_name'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table worker_lease add column lease_expires_at datetime', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'worker_lease' and column_name = 'lease_expires_at'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

create table if not exists studio_cluster_lock (
    id bigint primary key,
    lock_name varchar(255) not null,
    owner_id varchar(255),
    locked_until datetime,
    last_acquired_at datetime,
    created_at datetime default current_timestamp,
    updated_at datetime default current_timestamp,
    unique key uk_studio_cluster_lock_name (lock_name),
    key idx_studio_cluster_lock_until (locked_until)
);

set @ddl = (
    select if(count(*) = 0, 'alter table dispatch_task add key idx_dispatch_task_project_status_created (project_id, status, created_at)', 'select 1')
    from information_schema.statistics
    where table_schema = database() and table_name = 'dispatch_task' and index_name = 'idx_dispatch_task_project_status_created'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table worker_lease add key idx_worker_lease_code_instance (worker_code, instance_id)', 'select 1')
    from information_schema.statistics
    where table_schema = database() and table_name = 'worker_lease' and index_name = 'idx_worker_lease_code_instance'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table worker_lease add key idx_worker_lease_status_heartbeat (status, last_heartbeat_at)', 'select 1')
    from information_schema.statistics
    where table_schema = database() and table_name = 'worker_lease' and index_name = 'idx_worker_lease_status_heartbeat'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
