-- Data Aggregation Studio increment schema script
-- Target: user requested collection/run termination and worker cancellation markers.

set @ddl = (
    select if(count(*) = 0, 'alter table dispatch_task add column termination_requested int default 0', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'dispatch_task' and column_name = 'termination_requested'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table run_record add column termination_requested int default 0', 'select 1')
    from information_schema.columns
    where table_schema = database() and table_name = 'run_record' and column_name = 'termination_requested'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table dispatch_task add key idx_dispatch_task_termination_status (termination_requested, status, worker_instance_id)', 'select 1')
    from information_schema.statistics
    where table_schema = database() and table_name = 'dispatch_task' and index_name = 'idx_dispatch_task_termination_status'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0, 'alter table run_record add key idx_run_record_termination_status (termination_requested, status, worker_instance_id)', 'select 1')
    from information_schema.statistics
    where table_schema = database() and table_name = 'run_record' and index_name = 'idx_run_record_termination_status'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

update dispatch_task set termination_requested = 0 where termination_requested is null;
update run_record set termination_requested = 0 where termination_requested is null;
