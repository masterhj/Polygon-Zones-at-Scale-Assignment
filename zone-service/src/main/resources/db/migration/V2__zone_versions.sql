alter table tenants add column if not exists next_zone_set_version bigint not null default 1;

create table zone_set_versions (
  tenant_id uuid not null references tenants(id),
  version bigint not null,
  status text not null check (status in ('BUILDING','PUBLISHED','FAILED')),
  created_by uuid,
  created_at timestamptz not null default now(),
  published_at timestamptz,
  idempotency_key text,
  request_hash text,
  primary key (tenant_id, version),
  foreign key (tenant_id, created_by) references app_users (tenant_id, id),
  check ((status = 'PUBLISHED') = (published_at is not null)),
  check ((idempotency_key is null) = (request_hash is null))
);
create unique index zone_set_idempotency_key on zone_set_versions (tenant_id, idempotency_key) where idempotency_key is not null;

create table zones (
  tenant_id uuid not null references tenants(id),
  zone_id uuid not null,
  created_at timestamptz not null default now(),
  primary key (tenant_id, zone_id)
);

create table zone_versions (
  tenant_id uuid not null,
  zone_id uuid not null,
  version int not null,
  set_version bigint not null,
  name text not null,
  zone_type text not null check (zone_type in ('CITY','AIRPORT','TARIFF','NO_GO')),
  priority int not null default 0,
  rate_per_km numeric(12,4),
  surcharge numeric(12,2),
  geometry_geojson text,
  vertex_count int not null default 0,
  min_lon double precision,
  min_lat double precision,
  max_lon double precision,
  max_lat double precision,
  content_hash text,
  deleted boolean not null default false,
  created_by uuid,
  created_at timestamptz not null default now(),
  primary key (tenant_id, zone_id, version),
  unique (tenant_id, zone_id, set_version),
  unique (tenant_id, zone_id, version, set_version),
  foreign key (tenant_id, zone_id) references zones (tenant_id, zone_id),
  foreign key (tenant_id, set_version) references zone_set_versions (tenant_id, version),
  foreign key (tenant_id, created_by) references app_users (tenant_id, id),
  check (version > 0),
  check (vertex_count >= 0),
  check (deleted or (geometry_geojson is not null and content_hash is not null
      and min_lon is not null and min_lat is not null and max_lon is not null and max_lat is not null
      and min_lon <= max_lon and min_lat <= max_lat)),
  check (not deleted or geometry_geojson is null)
);
create index zone_versions_by_set on zone_versions (tenant_id, set_version);
create index zone_versions_latest on zone_versions (tenant_id, zone_id, set_version desc);
