-- Data Aggregation Studio increment schema script
-- Target: worker group binding for K8s multi-replica workers.

set @ddl = (
    select if(count(*) = 0, 'alter table studio_project_worker_binding add column worker_group_code varchar(255)', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'studio_project_worker_binding' and column_name = 'worker_group_code'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table worker_lease add column worker_group_code varchar(255)', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'worker_lease' and column_name = 'worker_group_code'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table dispatch_task add column worker_group_code varchar(255)', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'dispatch_task' and column_name = 'worker_group_code'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table run_record add column worker_group_code varchar(255)', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'run_record' and column_name = 'worker_group_code'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table run_record add column worker_pod_name varchar(255)', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'run_record' and column_name = 'worker_pod_name'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table run_record add column worker_node_name varchar(255)', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'run_record' and column_name = 'worker_node_name'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

update studio_project_worker_binding
set worker_group_code = worker_code
where (worker_group_code is null or worker_group_code = '') and worker_code is not null;

update worker_lease
set worker_group_code = worker_code
where (worker_group_code is null or worker_group_code = '') and worker_code is not null;

update dispatch_task
set worker_group_code = lease_owner
where (worker_group_code is null or worker_group_code = '') and lease_owner is not null;

update run_record
set worker_group_code = worker_code
where (worker_group_code is null or worker_group_code = '') and worker_code is not null;

set @ddl = (
    select if(count(*) = 0, 'alter table studio_project_worker_binding add key idx_studio_project_worker_group (project_id, worker_group_code)', 'select 1')
    from information_schema.statistics
    where table_schema = database() and table_name = 'studio_project_worker_binding' and index_name = 'idx_studio_project_worker_group'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table worker_lease add key idx_worker_lease_group_instance (worker_group_code, instance_id)', 'select 1')
    from information_schema.statistics
    where table_schema = database() and table_name = 'worker_lease' and index_name = 'idx_worker_lease_group_instance'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table dispatch_task add key idx_dispatch_task_group_status_created (worker_group_code, status, created_at)', 'select 1')
    from information_schema.statistics
    where table_schema = database() and table_name = 'dispatch_task' and index_name = 'idx_dispatch_task_group_status_created'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
