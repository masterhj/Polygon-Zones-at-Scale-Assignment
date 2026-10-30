create table tenants (
  id uuid primary key,
  name text not null unique,
  timezone text not null default 'Asia/Kolkata',
  night_start time not null default '22:00',
  night_end time not null default '06:00',
  created_at timestamptz not null default now()
);

create table app_users (
  id uuid primary key,
  tenant_id uuid not null references tenants(id),
  username text not null,
  password_hash text not null,
  role text not null check (role in ('EMPLOYEE','ADMIN','OPERATOR')),
  enabled boolean not null default true,
  created_at timestamptz not null default now(),
  unique (tenant_id, id),
  unique (tenant_id, username)
);
