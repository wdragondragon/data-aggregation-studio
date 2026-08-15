-- File transfer queue visibility is independent from durable run history.
set @schema_name = database();
set @sql = if((select count(*) from information_schema.columns
               where table_schema = @schema_name
                 and table_name = 'file_transfer_run'
                 and column_name = 'queue_visible') = 0,
  'alter table file_transfer_run add column queue_visible int not null default 1 after status',
  'select 1');
prepare statement_to_execute from @sql;
execute statement_to_execute;
deallocate prepare statement_to_execute;

update file_transfer_run
set queue_visible = 1
where queue_visible is null;
