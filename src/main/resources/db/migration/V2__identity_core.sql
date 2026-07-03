-- 身份域核心表(spec §5.1):C端账号、admin 账号、refresh token
-- username 规范化:唯一约束落在 canonical 列,规范化规则(trim+lowercase)在代码边界(第三批)

create table customer (
  id                 bigint generated always as identity primary key,
  username           text not null,                 -- 展示用原始输入
  username_canonical text not null,
  password_hash      text not null,
  invite_code        text not null,                 -- 注册时生成(第三批)
  -- invited_by 自引用:jOOQ 反向 join path 因命名冲突不生成(Ambiguous key name 警告,已知),反查邀请关系手写显式 join;
  -- 未建索引:系统不硬删用户,若引入硬删再新增迁移补 ix_customer_invited_by
  invited_by         bigint references customer(id),
  status             smallint not null default 1,   -- 1=active 0=disabled(人工封禁)
  last_login_at      timestamptz,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  unique (username_canonical),
  unique (invite_code),
  check (status in (0, 1))
);
create trigger trg_customer_updated before update on customer
  for each row execute function set_updated_at();

create table admin_user (
  id                  bigint generated always as identity primary key,
  username            text not null,
  username_canonical  text not null,
  password_hash       text not null,
  totp_secret_enc     bytea,                        -- AES-256-GCM:iv||ct||tag,KEK 注入(第三批实现)
  totp_key_version    smallint,                     -- 轮换=重加密
  totp_last_used_step bigint,                       -- 同 time step 防重放(CAS 递增,第三批)
  totp_enabled        boolean not null default false,
  status              smallint not null default 1,  -- 1=active 0=disabled
  last_login_at       timestamptz,
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  unique (username_canonical),
  check (status in (0, 1)),
  check ((totp_secret_enc is null) = (totp_key_version is null)),  -- 密材与版本同生同灭(第三批 AAD 绑定 key_version)
  check (not totp_enabled or totp_secret_enc is not null),         -- 启用必有密材(经上一条,版本号亦必有)
  check (totp_key_version is null or totp_key_version > 0),
  check (totp_last_used_step is null or totp_last_used_step >= 0)
);
create trigger trg_admin_user_updated before update on admin_user
  for each row execute function set_updated_at();

-- refresh token:明文不落库,存 SHA-256;旋转式 + family 吊销(spec §5.2)
create table refresh_token (
  id                 bigint generated always as identity primary key,
  family_id          uuid not null,
  principal_type     smallint not null,             -- 1=customer 2=admin
  principal_id       bigint not null,
  token_hash         bytea not null,                -- SHA-256(32 bytes)
  expires_at         timestamptz not null,
  rotated_at         timestamptz,                   -- 旋转后置位;已置位再现=重放
  revoked_at         timestamptz,                   -- 登出/改密/重放吊销
  last_used_at       timestamptz,
  created_ip         text,
  created_user_agent text,
  created_at         timestamptz not null default now(),
  unique (token_hash),
  check (principal_type in (1, 2)),
  check (octet_length(token_hash) = 32)                            -- 固化 SHA-256,防误存明文/hex 字符串
);
create index ix_refresh_token_principal on refresh_token (principal_type, principal_id);
create index ix_refresh_token_family on refresh_token (family_id);
create index ix_refresh_token_expires on refresh_token (expires_at);
