create table trips (
  tenant_id uuid not null references tenants(id),
  trip_id uuid not null,
  employee_id uuid not null,
  pinned_set_version bigint not null,
  status text not null check (status in ('IN_PROGRESS','COMPLETED','ABORTED')),
  started_at timestamptz not null,
  completed_at timestamptz,
  processed_seq bigint not null default -1,
  durable_acked_seq bigint not null default -1,
  checkpoint jsonb,
  checkpoint_at timestamptz,
  unzoned_meters double precision not null default 0,
  next_transition_ordinal bigint not null default 0,
  primary key (tenant_id, trip_id),
  unique (tenant_id, trip_id, pinned_set_version),
  foreign key (tenant_id, employee_id) references app_users (tenant_id, id),
  foreign key (tenant_id, pinned_set_version) references zone_set_versions (tenant_id, version),
  check (processed_seq >= -1),
  check (durable_acked_seq >= -1 and durable_acked_seq <= processed_seq),
  check (unzoned_meters >= 0),
  check (next_transition_ordinal >= 0)
);

create table trip_zone_distance (
  tenant_id uuid not null,
  trip_id uuid not null,
  zone_id uuid not null,
  zone_version int not null,
  zone_version_set_version bigint not null,
  pinned_set_version bigint not null,
  meters double precision not null,
  primary key (tenant_id, trip_id, zone_id),
  foreign key (tenant_id, trip_id, pinned_set_version)
    references trips (tenant_id, trip_id, pinned_set_version),
  foreign key (tenant_id, zone_id, zone_version, zone_version_set_version)
    references zone_versions (tenant_id, zone_id, version, set_version),
  check (meters >= 0)
);
