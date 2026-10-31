package com.polyzones.service.application;

import com.polyzones.core.geo.LonLat;
import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.service.publish.PinnedSnapshotRegistry;
import com.polyzones.service.publish.SnapshotRegistry;
import com.polyzones.service.security.TenantContext;
import com.polyzones.service.web.dto.PointQueryResponse;
import com.polyzones.service.web.dto.ZoneResponse;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import java.sql.Timestamp;

@Service
public class ZoneQueryService {
    private final SnapshotRegistry latest;
    private final PinnedSnapshotRegistry history;
    private final ZoneMapper mapper;
    private final JdbcTemplate jdbc;
    public ZoneQueryService(SnapshotRegistry latest, PinnedSnapshotRegistry history, ZoneMapper mapper, JdbcTemplate jdbc) {
        this.latest = latest; this.history = history; this.mapper = mapper; this.jdbc = jdbc;
    }

    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN') or (hasRole('EMPLOYEE') and #p0 == null)")
    public List<ZoneResponse> list(Long setVersion, int page, int size) {
        validatePage(page, size);
        UUID tenant = TenantContext.requireTenantId();
        return withSnapshot(tenant, setVersion, snapshot -> java.util.Arrays.stream(snapshot.zones())
                .skip((long) page * size).limit(size).map(zone -> mapper.toResponse(snapshot, zone)).toList());
    }

    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN') or (hasRole('EMPLOYEE') and #p1 == null)")
    public ZoneResponse get(UUID zoneId, Integer version) {
        UUID tenant = TenantContext.requireTenantId();
        Long setVersion = version == null ? null : jdbc.query("select set_version from zone_versions where tenant_id=? and zone_id=? and version=? and deleted=false",
                rs -> rs.next() ? rs.getLong(1) : null, tenant, zoneId, version);
        if (version != null && setVersion == null) throw notFound();
        return withSnapshot(tenant, setVersion, snapshot -> {
            Integer ordinal = snapshot.ordinalById().get(new ZoneKey(zoneId));
            if (ordinal == null) throw notFound();
            var zone = snapshot.zoneAt(ordinal);
            if (version != null && zone.version() != version) throw notFound();
            return mapper.toResponse(snapshot, zone);
        });
    }

    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public PointQueryResponse query(double lat, double lon, Long setVersion) {
        return withSnapshot(TenantContext.requireTenantId(), setVersion,
                snapshot -> new PointQueryResponse(snapshot.setVersion(), snapshot.index().query(lon, lat).stream().map(ZoneQueryService::hit).toList()));
    }

    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public List<com.polyzones.service.web.dto.ZoneVersionSummary> versions(java.util.UUID zoneId, int page, int size) {
        validatePage(page, size);
        UUID tenant = TenantContext.requireTenantId();
        Integer exists = jdbc.query("select 1 from zones where tenant_id=? and zone_id=?", rs -> rs.next() ? 1 : null,
                tenant, zoneId);
        if (exists == null) throw notFound();
        return jdbc.query("select version,set_version,deleted,created_at from zone_versions where tenant_id=? and zone_id=? order by version desc limit ? offset ?",
                (rs, row) -> new com.polyzones.service.web.dto.ZoneVersionSummary(rs.getInt(1), rs.getLong(2), rs.getBoolean(3), rs.getTimestamp(4).toInstant()),
                tenant, zoneId, size, (long) page * size);
    }

    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public List<com.polyzones.service.web.dto.ZoneSetSummary> sets(int page, int size) {
        validatePage(page, size);
        UUID tenant = TenantContext.requireTenantId();
        return jdbc.query("select version,status,created_at,published_at from zone_set_versions where tenant_id=? order by version desc limit ? offset ?",
                (rs, row) -> new com.polyzones.service.web.dto.ZoneSetSummary(rs.getLong(1), rs.getString(2),
                        rs.getTimestamp(3).toInstant(), rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()),
                tenant, size, (long) page * size);
    }

    @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
    public List<PointQueryResponse> batch(List<com.polyzones.service.web.dto.PointQueryRequest> points) {
        if (points == null || points.isEmpty() || points.size() > 1000) throw new ApplicationException("VALIDATION_FAILED", "A batch must contain between 1 and 1000 points.");
        UUID tenant = TenantContext.requireTenantId();
        Long version = points.getFirst().asOfSetVersion();
        if (points.stream().anyMatch(p -> !java.util.Objects.equals(p.asOfSetVersion(), version))) throw new ApplicationException("VALIDATION_FAILED", "All points in a batch must use the same set version.");
        return withSnapshot(tenant, version, snapshot -> points.stream()
                .map(point -> new PointQueryResponse(snapshot.setVersion(), snapshot.index().query(point.lon(), point.lat()).stream().map(ZoneQueryService::hit).toList())).toList());
    }

    private <T> T withSnapshot(UUID tenant, Long version, Function<ZoneSnapshot, T> action) {
        boolean acquired = version != null;
        ZoneSnapshot snapshot;
        try { snapshot = acquired ? history.acquire(tenant, version) : latest.latest(tenant); }
        catch (PinnedSnapshotRegistry.VersionNotFoundException | IllegalStateException missing) { throw notFound(); }
        try { return action.apply(snapshot); }
        finally { if (acquired) history.release(tenant, version); }
    }
    private static void validatePage(int page, int size) { if (page < 0 || size < 1 || size > 200) throw new ApplicationException("VALIDATION_FAILED", "Pagination must use a non-negative page and size from 1 to 200."); }
    private static ApplicationException notFound() { return new ApplicationException("ZONE_NOT_FOUND", "The requested zone was not found."); }
    private static com.polyzones.service.web.dto.ZoneHitResponse hit(com.polyzones.core.index.ZoneHit hit) {
        return new com.polyzones.service.web.dto.ZoneHitResponse(hit.id().value(), hit.version(), hit.type(), hit.priority(), hit.location());
    }
}
