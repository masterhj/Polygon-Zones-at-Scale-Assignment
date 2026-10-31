alter table alerts add column row_version bigint not null default 0;

create index app_users_by_tenant on app_users (tenant_id);
create index zone_sets_created_by on zone_set_versions (tenant_id, created_by);
create index zone_versions_created_by on zone_versions (tenant_id, created_by);
create index trips_by_employee on trips (tenant_id, employee_id, started_at desc);
create index zone_events_evaluated_set on zone_events (tenant_id, evaluated_set_version);
create index alerts_evaluated_set on alerts (tenant_id, evaluated_set_version);
create index alerts_source_event on alerts (tenant_id, source_event_id);
