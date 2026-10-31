package com.polyzones.service.persistence;

import com.polyzones.core.index.SnapshotBuilder;
import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.index.ZoneIndexFactory;
import com.polyzones.core.index.RTreeZoneIndex;
import com.polyzones.core.geo.GeometryTolerance;
import com.polyzones.core.shape.PolygonShape;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.core.zone.ZoneType;
import com.polyzones.service.publish.SnapshotLoader;
import com.polyzones.service.publish.PinnedSnapshotRegistry;
import java.util.List;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Reconstructs snapshots only from the status-authorized version rows. */
public final class JdbcSnapshotLoader implements SnapshotLoader {
    private static final String VERSIONS = """
            select distinct on (zv.zone_id) zv.zone_id, zv.version, zv.zone_type, zv.priority, zv.geometry_geojson
              from zone_versions zv
              join zone_set_versions zsv on zsv.tenant_id = zv.tenant_id and zsv.version = zv.set_version
             where zv.tenant_id = ? and zv.set_version <= ? and zv.deleted = false and (
                   zsv.status = 'PUBLISHED' or (zsv.version = ? and zsv.status = 'BUILDING'))
             order by zv.zone_id, zv.set_version desc
            """;
    private static final String PUBLISHED_VERSION = "select 1 from zone_set_versions where tenant_id=? and version=? and status='PUBLISHED'";
    private static final String BUILDING_VERSION = "select 1 from zone_set_versions where tenant_id=? and version=? and status='BUILDING'";
    private final JdbcTemplate jdbc;
    private final GeometryCodec geometry;
    private final ZoneIndexFactory indexes;
    private final Clock clock;

    public JdbcSnapshotLoader(JdbcTemplate jdbc, GeometryCodec geometry, ZoneIndexFactory indexes, Clock clock) {
        this.jdbc = jdbc; this.geometry = geometry; this.indexes = indexes; this.clock = clock;
    }

    @Override public ZoneSnapshot loadPublished(UUID tenantId, long setVersion) {
        if (!exists(PUBLISHED_VERSION, tenantId, setVersion)) throw new PinnedSnapshotRegistry.VersionNotFoundException();
        return build(tenantId, setVersion);
    }

    @Override public ZoneSnapshot loadCandidate(UUID tenantId, long buildingSetVersion) {
        if (!exists(BUILDING_VERSION, tenantId, buildingSetVersion)) throw new IllegalStateException("Candidate set is not BUILDING");
        return build(tenantId, buildingSetVersion);
    }

    private boolean exists(String sql, UUID tenant, long version) {
        return !jdbc.query(sql, (rs, row) -> Boolean.TRUE, tenant, version).isEmpty();
    }

    private ZoneSnapshot build(UUID tenant, long version) {
        List<Zone> zones = jdbc.query(VERSIONS, (RowMapper<Zone>) (rs, row) -> {
            PolygonShape shape = geometry.decode(rs.getString("geometry_geojson"));
            return new Zone(new ZoneKey(rs.getObject("zone_id", UUID.class)), rs.getInt("version"),
                    ZoneType.valueOf(rs.getString("zone_type")), rs.getInt("priority"), shape);
        }, tenant, version, version);
        return SnapshotBuilder.build(tenant, version, zones, GeometryTolerance.DEFAULT, clock,
                RTreeZoneIndex.DEFAULT_NODE_CAPACITY, indexes);
    }
}
