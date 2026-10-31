create index trips_in_progress on trips (tenant_id, status, started_at) where status = 'IN_PROGRESS';
create index trip_zone_distance_trip on trip_zone_distance (tenant_id, trip_id);
create index trip_zone_distance_zone_version on trip_zone_distance (tenant_id, zone_id, zone_version, zone_version_set_version);
create index zone_events_trip on zone_events (tenant_id, trip_id, event_time);
create index zone_events_zone_version on zone_events (tenant_id, zone_id, zone_version, zone_version_set_version);
create index alerts_tenant_time on alerts (tenant_id, event_time desc);
create index alerts_trip on alerts (tenant_id, trip_id, event_time);
create index alerts_zone_version on alerts (tenant_id, zone_id, zone_version, zone_version_set_version);
