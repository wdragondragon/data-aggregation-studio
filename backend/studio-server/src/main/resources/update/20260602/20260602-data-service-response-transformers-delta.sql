-- Data service response field transformer configuration.

set @ddl := (
    select if(count(*) = 0,
        'alter table data_service_response_param add column transformers_json json after description',
        'select 1')
    from information_schema.columns
    where table_schema = database()
      and table_name = 'data_service_response_param'
      and column_name = 'transformers_json'
);
prepare stmt from @ddl;
execute stmt;
deallocate prepare stmt;
