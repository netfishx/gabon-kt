-- V4__app_role_grants.sql:app runtime role 与 migration owner 分离(spec §2.1 ②/§2.3)
-- 前提:本文件由 migration owner 执行;Flyway/codegen/测试三处 owner 必须一致——
-- alter default privileges 只影响执行者(owner)后续创建的对象,owner 不一致则默认授权不生效。
-- 生产部署前提(二选一):预先创建 gabon_app(推荐),或给迁移 role CREATEROLE;
-- DO guard 只负责幂等,不绕过集群权限模型。生产密码由部署侧 ALTER ROLE 预置/secret 管理。
do $$
begin
  if not exists (select from pg_roles where rolname = 'gabon_app') then
    create role gabon_app login;
  end if;
end $$;

grant usage on schema public to gabon_app;
grant select, insert, update, delete on all tables in schema public to gabon_app;
grant usage, select on all sequences in schema public to gabon_app;  -- identity 列底层 sequence

-- append-only:先 REVOKE 再收窄 GRANT(不依赖默认权限状态);"只追加"由此保证
revoke all on table ledger_txn, ledger_entry from gabon_app;
grant select, insert on table ledger_txn, ledger_entry to gabon_app;

-- 迁移史表不属于 app
revoke all on table flyway_schema_history from gabon_app;

-- 未来迁移新建对象的默认授权(免 V5+ 每张新表手工 GRANT);
-- 未来 append-only 新表在其自己的迁移里显式 REVOKE
alter default privileges in schema public grant select, insert, update, delete on tables to gabon_app;
alter default privileges in schema public grant usage, select on sequences to gabon_app;
