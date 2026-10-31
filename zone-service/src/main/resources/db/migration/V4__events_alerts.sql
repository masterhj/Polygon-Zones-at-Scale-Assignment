create table zone_events (
  event_id uuid not null,
  tenant_id uuid not null,
  trip_id uuid not null,
  zone_id uuid not null,
  zone_version int not null,
  zone_version_set_version bigint not null,
  evaluated_set_version bigint not null,
  transition_ordinal bigint not null,
  kind text not null check (kind in ('ENTER','EXIT')),
  event_time timestamptz not null,
  dwell_seconds double precision,
  reason text not null default 'CROSSING',
  primary key (tenant_id, event_id),
  unique (tenant_id, trip_id, transition_ordinal),
  unique (tenant_id, event_id, trip_id, zone_id, zone_version, zone_version_set_version, evaluated_set_version),
  foreign key (tenant_id, trip_id) references trips (tenant_id, trip_id),
  foreign key (tenant_id, zone_id, zone_version, zone_version_set_version)
    references zone_versions (tenant_id, zone_id, version, set_version),
  foreign key (tenant_id, evaluated_set_version)
    references zone_set_versions (tenant_id, version),
  check (transition_ordinal >= 1),
  check (dwell_seconds is null or dwell_seconds >= 0)
);

create table alerts (
  alert_id uuid not null,
  tenant_id uuid not null,
  trip_id uuid not null,
  zone_id uuid not null,
  source_event_id uuid not null,
  kind text not null check (kind in ('NO_GO_NIGHT_ENTRY')),
  severity text not null,
  event_time timestamptz not null,
  zone_version int not null,
  zone_version_set_version bigint not null,
  evaluated_set_version bigint not null,
  created_at timestamptz not null default now(),
  acknowledged boolean not null default false,
  primary key (tenant_id, alert_id),
  unique (tenant_id, source_event_id, kind),
  foreign key (tenant_id, trip_id) references trips (tenant_id, trip_id),
  foreign key (tenant_id, zone_id, zone_version, zone_version_set_version)
    references zone_versions (tenant_id, zone_id, version, set_version),
  foreign key (tenant_id, evaluated_set_version)
    references zone_set_versions (tenant_id, version),
  foreign key (tenant_id, source_event_id, trip_id, zone_id, zone_version,
      zone_version_set_version, evaluated_set_version)
    references zone_events (tenant_id, event_id, trip_id, zone_id, zone_version,
      zone_version_set_version, evaluated_set_version)
);
