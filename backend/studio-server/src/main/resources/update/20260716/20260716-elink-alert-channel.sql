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

-- One Studio user can bind at most one account for each external provider.
set @ddl = (
    select if(count(*) = 0,
        'alter table studio_external_user_binding add unique key uk_studio_external_user_binding_provider_user (provider_code, studio_user_id)',
        'select 1')
    from information_schema.statistics
    where table_schema = database()
      and table_name = 'studio_external_user_binding'
      and index_name = 'uk_studio_external_user_binding_provider_user'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
