-- 通用 updated_at 触发器（PG 无 MySQL 的 ON UPDATE CURRENT_TIMESTAMP）
create or replace function set_updated_at() returns trigger as $$
begin new.updated_at = now(); return new; end $$ language plpgsql;

-- 账户：余额投影列 + 乐观锁版本
create table account (
  id         bigint generated always as identity primary key,
  owner_kind smallint not null,        -- 1=customer, 0=platform
  owner_id   bigint   not null,        -- customer_id 或 0
  kind       smallint not null,        -- 1=available 2=frozen 3=payment_clearing 4=payout_clearing 5=platform_equity
  balance    bigint   not null default 0,
  version    bigint   not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (owner_kind, owner_id, kind),
  check (kind not in (1,2) or balance >= 0)   -- 仅用户账户强制非负
);
create trigger trg_account_updated before update on account
  for each row execute function set_updated_at();

-- 记账事务（日记账头）：★ 幂等键 (biz_type, biz_no)
create table ledger_txn (
  id         bigint generated always as identity primary key,
  biz_type   smallint not null,
  biz_no     text     not null,
  memo       text,
  created_at timestamptz not null default now(),
  unique (biz_type, biz_no)            -- 重复业务只记一次
);

-- 记账明细（日记账行）：只追加，一笔 txn ≥2 行，Σamount=0
create table ledger_entry (
  id         bigint generated always as identity primary key,
  txn_id     bigint not null references ledger_txn(id),
  account_id bigint not null references account(id),
  amount     bigint not null,          -- 有符号：+入 / -出
  created_at timestamptz not null default now()
);
create index ix_ledger_entry_account on ledger_entry (account_id, id);

-- 每 txn 借贷必须平（Σamount=0）且至少 2 行：延迟到提交时校验
create or replace function assert_txn_balanced() returns trigger as $$
declare v_sum bigint; v_cnt int;
begin
  select coalesce(sum(amount), 0), count(*) into v_sum, v_cnt
    from ledger_entry where txn_id = new.txn_id;
  if v_cnt < 2 or v_sum <> 0 then
    raise exception 'ledger txn % invalid: rows=%, sum=%', new.txn_id, v_cnt, v_sum;
  end if;
  return null;
end $$ language plpgsql;
create constraint trigger trg_ledger_balanced
  after insert on ledger_entry
  deferrable initially deferred
  for each row execute function assert_txn_balanced();

-- 入站回调去重
create table inbox (
  id          bigint generated always as identity primary key,
  source      smallint not null,
  external_id text     not null,
  received_at timestamptz not null default now(),
  unique (source, external_id)
);

-- 出站事务性 outbox（与业务同库同事务写入）
create table outbox (
  id           bigint generated always as identity primary key,
  aggregate    text     not null,
  event_type   smallint not null,
  payload      jsonb    not null,
  status       smallint not null default 0,   -- 0=ready 1=in_flight 2=done 3=dead
  attempts     int      not null default 0,
  max_attempts int      not null default 8,
  next_run_at  timestamptz not null default now(),
  lease_until  timestamptz,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now()
);
create index ix_outbox_poll on outbox (status, next_run_at, id);
create trigger trg_outbox_updated before update on outbox
  for each row execute function set_updated_at();
