-- File-operation failures can contain remote protocol diagnostics. Upgrade legacy
-- VARCHAR audit storage before those messages are recorded.
set @schema_name = database();

set @sql = if((select count(*) from information_schema.columns
    where table_schema = @schema_name
      and table_name = 'unstructured_op_audit'
      and column_name = 'message'
      and data_type <> 'text') > 0,
  'alter table unstructured_op_audit modify column message text',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;
