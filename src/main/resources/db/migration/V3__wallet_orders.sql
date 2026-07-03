-- V3__wallet_orders.sql:充值档位/充值订单/提现订单(spec §2.2)
-- 法币金额只在订单侧(cents),不进钻石账本;customer_id 仅存 id,不跨上下文 FK

create table recharge_package (
  id          bigint generated always as identity primary key,
  diamonds    bigint   not null check (diamonds > 0),
  price_cents bigint   not null check (price_cents > 0),
  currency    char(3)  not null check (currency ~ '^[A-Z]{3}$'),
  status      smallint not null default 1 check (status in (0, 1)),  -- 1=上架 0=下架
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create trigger trg_recharge_package_updated before update on recharge_package
  for each row execute function set_updated_at();

create table recharge_order (
  id               bigint generated always as identity primary key,
  order_no         text     not null check (order_no ~ '\S'),
  customer_id      bigint   not null,
  package_id       bigint   not null references recharge_package(id),
  diamonds         bigint   not null check (diamonds > 0),      -- 档位快照,防在途改价
  price_cents      bigint   not null check (price_cents > 0),
  currency         char(3)  not null check (currency ~ '^[A-Z]{3}$'),
  channel          smallint not null check (channel > 0),
  channel_order_no text     check (channel_order_no ~ '\S'),
  status           smallint not null default 1 check (status in (1, 2, 3, 4, 5)),
    -- 1=CREATED 2=PROCESSING 3=SUCCESS 4=FAILED 5=CANCELLED(C2.4)
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  unique (order_no)
);
create trigger trg_recharge_order_updated before update on recharge_order
  for each row execute function set_updated_at();
create index ix_recharge_order_customer on recharge_order (customer_id, id);
-- 一渠道流水号只映射一本地单(防重之外的映射约束,spec §2.2)
create unique index ux_recharge_order_channel_no
  on recharge_order (channel, channel_order_no) where channel_order_no is not null;

create table withdraw_order (
  id                bigint generated always as identity primary key,
  order_no          text     not null check (order_no ~ '\S'),
  customer_id       bigint   not null,
  diamonds          bigint   not null check (diamonds > 0),
  payout_cents      bigint   not null check (payout_cents > 0),  -- 固定汇率换算快照
  currency          char(3)  not null check (currency ~ '^[A-Z]{3}$'),
  payout_account    jsonb    not null check (jsonb_typeof(payout_account) = 'object'),
  channel           smallint not null check (channel > 0),
  channel_payout_no text     check (channel_payout_no ~ '\S'),
  review_memo       text,
  reviewed_by       bigint,
  reviewed_at       timestamptz,
  status            smallint not null default 1 check (status in (1, 2, 3, 4, 5, 6)),
    -- 1=PENDING 2=APPROVED 3=PROCESSING 4=SUCCESS 5=FAILED 6=REJECTED(C2.4)
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  unique (order_no),
  check ((reviewed_by is null) = (reviewed_at is null)),  -- 同空同非空
  check (status = 1 or reviewed_at is not null)           -- PENDING 之外必有审批留痕
);
create trigger trg_withdraw_order_updated before update on withdraw_order
  for each row execute function set_updated_at();
create index ix_withdraw_order_customer on withdraw_order (customer_id, id);
create index ix_withdraw_order_status on withdraw_order (status, id);
create unique index ux_withdraw_order_channel_no
  on withdraw_order (channel, channel_payout_no) where channel_payout_no is not null;
