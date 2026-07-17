-- Store the Studio user's mobile number for rule-recipient eLink fallback.
-- Keep the delta idempotent because StudioSchemaUpgradeService may run first.
set @ddl = (
    select if(count(*) = 0,
        'alter table sys_user add column mobile_phone varchar(32) after display_name',
        'select 1')
    from information_schema.columns
    where table_schema = database()
      and table_name = 'sys_user'
      and column_name = 'mobile_phone'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
