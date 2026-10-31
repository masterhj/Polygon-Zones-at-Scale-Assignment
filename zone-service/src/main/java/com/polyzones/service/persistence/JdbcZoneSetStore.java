package com.polyzones.service.persistence;

import com.polyzones.core.geo.BoundingBox;
import com.polyzones.core.shape.PolygonShape;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.service.publish.ZoneEditCommand;
import com.polyzones.service.publish.ZoneSetPublisher;
import com.polyzones.service.publish.ZoneSetStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL implementation of the short publication transactions. */
public final class JdbcZoneSetStore implements ZoneSetStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final GeometryCodec geometry;
    private final JdbcSnapshotLoader snapshots;
    private final Clock clock;

    public JdbcZoneSetStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                            GeometryCodec geometry, JdbcSnapshotLoader snapshots, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.geometry = geometry;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    @Override public long beginBuild(UUID tenantId, ZoneEditCommand command) {
        if (command.idempotencyKey() != null && command.bodyHash() == null) throw new IllegalArgumentException("Idempotency-Key requires a body hash");
        Set<ZoneKey> changed = new HashSet<>();
        for (Zone zone : command.upserts()) if (!changed.add(zone.id())) throw new IllegalArgumentException("Duplicate zone in edit");
        for (ZoneKey key : command.deletes()) if (!changed.add(key)) throw new IllegalArgumentException("Zone cannot be both changed and deleted");
        Long result = transactions.execute(status -> {
            Long next = jdbc.query("select next_zone_set_version from tenants where id=? for update",
                    rs -> rs.next() ? rs.getLong(1) : null, tenantId);
            if (next == null) throw new IllegalArgumentException("Tenant not found");
            if (command.createOnly()) {
                for (Zone zone : command.upserts()) {
                    Integer exists = jdbc.query("select 1 from zone_versions zv join zone_set_versions zsv "
                                    + "on zsv.tenant_id=zv.tenant_id and zsv.version=zv.set_version "
                                    + "where zv.tenant_id=? and zv.zone_id=? and zsv.status='PUBLISHED' limit 1",
                            rs -> rs.next() ? 1 : null, tenantId, zone.id().value());
                    if (exists != null) throw new ZoneAlreadyExistsException();
                }
            }
            Long max = jdbc.queryForObject("select coalesce(max(version),0) from zone_set_versions where tenant_id=?", Long.class, tenantId);
            long version = Math.max(next, (max == null ? 0L : max) + 1L);
            jdbc.update("update tenants set next_zone_set_version=? where id=?", Math.addExact(version, 1L), tenantId);
            try {
                jdbc.update("insert into zone_set_versions(tenant_id,version,status,idempotency_key,request_hash) values (?,?,'BUILDING',?,?)",
                        tenantId, version, command.idempotencyKey(),
                        command.idempotencyKey() == null ? null : command.bodyHash());
            } catch (DuplicateKeyException duplicate) {
                throw new IdempotencyConflictException();
            }
            for (Zone zone : command.upserts()) insertVersion(tenantId, version, zone);
            for (ZoneKey zoneId : command.deletes()) insertTombstone(tenantId, version, zoneId);
            return version;
        });
        if (result == null) throw new IllegalStateException("Zone-set allocation returned no version");
        return result;
    }

    private void insertVersion(UUID tenant, long setVersion, Zone zone) {
        UUID zoneId = zone.id().value();
        jdbc.update("insert into zones(tenant_id,zone_id) values (?,?) on conflict do nothing", tenant, zoneId);
        Integer version = jdbc.query("select coalesce(max(version),0)+1 from zone_versions where tenant_id=? and zone_id=?",
                rs -> { rs.next(); return rs.getInt(1); }, tenant, zoneId);
        String encoded = geometry.encode(zone.shape());
        BoundingBox bounds = zone.shape().bounds();
        jdbc.update("""
                insert into zone_versions(tenant_id,zone_id,version,set_version,name,zone_type,priority,geometry_geojson,
                    vertex_count,min_lon,min_lat,max_lon,max_lat,content_hash,deleted)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,false)
                """, tenant, zoneId, version, setVersion, zoneId.toString(), zone.type().name(), zone.priority(), encoded,
                zone.shape().vertexCount(), bounds.minLon(), bounds.minLat(), bounds.maxLon(), bounds.maxLat(), sha256(encoded));
    }

    private void insertTombstone(UUID tenant, long setVersion, ZoneKey zoneKey) {
        UUID zoneId = zoneKey.value();
        Integer exists = jdbc.query("select 1 from zones where tenant_id=? and zone_id=?", rs -> rs.next() ? 1 : null, tenant, zoneId);
        if (exists == null) throw new IllegalArgumentException("Cannot delete an unknown zone");
        Integer version = jdbc.query("select coalesce(max(version),0)+1 from zone_versions where tenant_id=? and zone_id=?",
                rs -> { rs.next(); return rs.getInt(1); }, tenant, zoneId);
        jdbc.update("""
                insert into zone_versions(tenant_id,zone_id,version,set_version,name,zone_type,priority,vertex_count,deleted)
                select ?,?,?,?,'DELETED',latest.zone_type,latest.priority,0,true
                  from zone_versions latest
                 where latest.tenant_id=? and latest.zone_id=?
                 order by latest.set_version desc limit 1
                """, tenant, zoneId, version, setVersion, tenant, zoneId);
    }

    @Override public void markPublished(UUID tenantId, long setVersion) {
        int updated = transactions.execute(status -> jdbc.update("update zone_set_versions set status='PUBLISHED',published_at=? where tenant_id=? and version=? and status='BUILDING'",
                Timestamp.from(clock.instant()), tenantId, setVersion));
        if (updated != 1) throw new IllegalStateException("Zone set is not BUILDING");
    }

    @Override public void markFailed(UUID tenantId, long setVersion) {
        transactions.executeWithoutResult(status -> jdbc.update("update zone_set_versions set status='FAILED' where tenant_id=? and version=? and status='BUILDING'",
                tenantId, setVersion));
    }

    @Override public ZoneSetPublisher.PublishResult findIdempotent(UUID tenantId, String key, String bodyHash) {
        if (key == null) return null;
        Idempotency row = jdbc.query("select version,status,request_hash from zone_set_versions where tenant_id=? and idempotency_key=?",
                rs -> rs.next() ? new Idempotency(rs.getLong(1), rs.getString(2), rs.getString(3)) : null, tenantId, key);
        if (row == null) return null;
        if (!row.bodyHash.equals(bodyHash)) throw new IdempotencyConflictException();
        if ("BUILDING".equals(row.status)) return null;
        if ("FAILED".equals(row.status)) throw new ZoneSetPublisher.PublishFailedException(new IllegalStateException("Previous idempotent edit failed"));
        return new ZoneSetPublisher.PublishResult(tenantId, row.version, snapshots.loadPublished(tenantId, row.version));
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private record Idempotency(long version, String status, String bodyHash) { }

    public static final class IdempotencyConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public IdempotencyConflictException() { super("Idempotency key was already used with another request"); }
    }
    public static final class ZoneAlreadyExistsException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public ZoneAlreadyExistsException() { super("Zone already exists"); }
    }
}
