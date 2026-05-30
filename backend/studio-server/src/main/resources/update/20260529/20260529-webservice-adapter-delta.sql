-- Data Aggregation Studio increment schema script
-- Base: 2026-05-29 data ingestion service version.
-- Target: add optional SOAP WebService adapter settings for data services and data ingestion services.

set @ddl = (
    select if(count(*) = 0,
        'alter table data_service_definition add column webservice_enabled int default 0 after default_subscription_name',
        'select 1')
    from information_schema.columns
    where table_schema = database()
      and table_name = 'data_service_definition'
      and column_name = 'webservice_enabled'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_service_definition add column webservice_config_json json after webservice_enabled',
        'select 1')
    from information_schema.columns
    where table_schema = database()
      and table_name = 'data_service_definition'
      and column_name = 'webservice_config_json'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

update data_service_definition
set webservice_enabled = 0
where webservice_enabled is null;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_service add column webservice_enabled int default 0 after default_subscription_name',
        'select 1')
    from information_schema.columns
    where table_schema = database()
      and table_name = 'data_ingestion_service'
      and column_name = 'webservice_enabled'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_service add column webservice_config_json json after webservice_enabled',
        'select 1')
    from information_schema.columns
    where table_schema = database()
      and table_name = 'data_ingestion_service'
      and column_name = 'webservice_config_json'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

update data_ingestion_service
set webservice_enabled = 0
where webservice_enabled is null;
