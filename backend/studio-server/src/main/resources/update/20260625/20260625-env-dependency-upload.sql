-- Script environment dependency upload delta.
-- Adds script_type for existing so_pf_env_dep rows; historical dependency packages default to JAVA.

set @schema_name = database();
set @sql = if((select count(*) from information_schema.columns where table_schema = @schema_name and table_name = 'so_pf_env_dep' and column_name = 'script_type') = 0,
  'alter table so_pf_env_dep add column script_type varchar(32) default ''JAVA'' after version',
  'select 1');
prepare stmt from @sql;
execute stmt;
deallocate prepare stmt;

update so_pf_env_dep set script_type = 'JAVA' where script_type is null or script_type = '';
