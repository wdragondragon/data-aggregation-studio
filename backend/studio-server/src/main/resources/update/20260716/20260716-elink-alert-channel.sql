-- Add the generic channel configuration column used by the minimal eLink target settings.
-- Keep this delta idempotent because StudioSchemaUpgradeService may have added the column first.
set @ddl = (
    select if(count(*) = 0,
        'alter table studio_alert_channel add column config_json json after signing_secret_ciphertext',
        'select 1')
    from information_schema.columns
    where table_schema = database()
      and table_name = 'studio_alert_channel'
      and column_name = 'config_json'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
