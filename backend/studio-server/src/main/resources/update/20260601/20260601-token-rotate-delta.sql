-- Data service and data ingestion service subscription token rotation metadata.

set @ddl = (
    select if(count(*) = 0,
        'alter table data_service_subscription add column token_masked varchar(64) after token_hash',
        'select 1')
    from information_schema.columns
    where table_schema = database()
      and table_name = 'data_service_subscription'
      and column_name = 'token_masked'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_service_subscription add column rotated_at datetime after last_used_at',
        'select 1')
    from information_schema.columns
    where table_schema = database()
      and table_name = 'data_service_subscription'
      and column_name = 'rotated_at'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_service_subscription add column rotated_by bigint after rotated_at',
        'select 1')
    from information_schema.columns
    where table_schema = database()
      and table_name = 'data_service_subscription'
      and column_name = 'rotated_by'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_subscription add column token_masked varchar(64) after token_hash',
        'select 1')
    from information_schema.columns
    where table_schema = database()
      and table_name = 'data_ingestion_subscription'
      and column_name = 'token_masked'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_subscription add column rotated_at datetime after last_used_at',
        'select 1')
    from information_schema.columns
    where table_schema = database()
      and table_name = 'data_ingestion_subscription'
      and column_name = 'rotated_at'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;

set @ddl = (
    select if(count(*) = 0,
        'alter table data_ingestion_subscription add column rotated_by bigint after rotated_at',
        'select 1')
    from information_schema.columns
    where table_schema = database()
      and table_name = 'data_ingestion_subscription'
      and column_name = 'rotated_by'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
