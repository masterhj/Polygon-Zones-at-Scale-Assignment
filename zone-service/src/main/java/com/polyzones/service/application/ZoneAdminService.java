package com.polyzones.service.application;

import com.polyzones.core.index.ZoneSnapshot;
import com.polyzones.core.shape.InvalidPolygonException;
import com.polyzones.core.shape.PolygonShape;
import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import com.polyzones.service.publish.ZoneEditCommand;
import com.polyzones.service.publish.SnapshotRegistry;
import com.polyzones.service.publish.ZoneSetPublisher;
import com.polyzones.service.security.TenantContext;
import com.polyzones.service.web.dto.ZoneEditRequest;
import com.polyzones.service.web.dto.ZoneResponse;
import com.polyzones.service.web.dto.ValidationIssueResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;

@Service
public class ZoneAdminService {
    private final ZoneMapper mapper;
    private final ZoneSetPublisher publisher;
    private final SnapshotRegistry snapshots;

    public ZoneAdminService(ZoneMapper mapper, ZoneSetPublisher publisher, SnapshotRegistry snapshots) {
        this.mapper = mapper; this.publisher = publisher; this.snapshots = snapshots;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public List<ValidationIssueResponse> validate(ZoneEditRequest request) {
        try { mapper.toShape(request.geometry()); return List.of(); }
        catch (InvalidPolygonException invalid) { return invalid.result().issues().stream().map(issue -> new ValidationIssueResponse(issue.code().name(), issue.ringIndex(), issue.detail())).toList(); }
        catch (IllegalArgumentException invalid) { throw new ApplicationException("VALIDATION_FAILED", "The zone geometry is invalid."); }
    }

    @PreAuthorize("hasRole('ADMIN')")
    public ZoneResponse create(ZoneEditRequest request, String idempotencyKey) {
        UUID tenant = TenantContext.requireTenantId();
        UUID id = request.zoneId() == null ? (idempotencyKey == null ? UUID.randomUUID() : UUID.nameUUIDFromBytes((tenant + ":" + idempotencyKey).getBytes(java.nio.charset.StandardCharsets.UTF_8))) : request.zoneId();
        PolygonShape shape = validatedShape(request);
        Zone zone = new Zone(new ZoneKey(id), 1, request.type(), request.priority(), shape);
        return publish(tenant, new ZoneEditCommand(List.of(zone), List.of(), idempotencyKey, hash(request, id, shape), true), zone);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public ZoneResponse update(UUID zoneId, ZoneEditRequest request, String idempotencyKey) {
        UUID tenant = TenantContext.requireTenantId();
        ZoneSnapshot current = latest(tenant);
        Integer ordinal = current.ordinalById().get(new ZoneKey(zoneId));
        if (ordinal == null) throw notFound();
        if (request.zoneId() != null && !zoneId.equals(request.zoneId())) throw new ApplicationException("VALIDATION_FAILED", "The zone identifier does not match the request path.");
        Zone prior = current.zoneAt(ordinal);
        PolygonShape shape = validatedShape(request);
        Zone zone = new Zone(prior.id(), Math.addExact(prior.version(), 1), request.type(), request.priority(), shape);
        return publish(tenant, new ZoneEditCommand(List.of(zone), List.of(), idempotencyKey, hash(request, zone.id().value(), shape)), zone);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public long delete(UUID zoneId, String idempotencyKey) {
        UUID tenant = TenantContext.requireTenantId();
        ZoneSnapshot current = latest(tenant);
        if (!current.ordinalById().containsKey(new ZoneKey(zoneId))) throw notFound();
        try {
            return publisher.publish(tenant, new ZoneEditCommand(List.of(), List.of(new ZoneKey(zoneId)), idempotencyKey,
                    idempotencyKey == null ? null : "delete:" + zoneId), Duration.ofSeconds(30)).setVersion();
        } catch (com.polyzones.service.persistence.JdbcZoneSetStore.IdempotencyConflictException conflict) {
            throw new ApplicationException("IDEMPOTENCY_CONFLICT", "The idempotency key was already used for another request.");
        } catch (ZoneSetPublisher.PublicationTimeoutException timeout) {
            throw new ApplicationException("ZONE_SET_UNAVAILABLE", "Zone publication capacity is temporarily unavailable.");
        } catch (ZoneSetPublisher.PublishFailedException failure) {
            throw new ApplicationException("PUBLISH_FAILED", "The zone change could not be published.");
        }
    }

    private ZoneResponse publish(UUID tenant, ZoneEditCommand command, Zone zone) {
        try {
            ZoneSnapshot snapshot = publisher.publish(tenant, command, Duration.ofSeconds(30)).snapshot();
            Integer ordinal = snapshot.ordinalById().get(zone.id());
            if (ordinal == null) throw notFound();
            return mapper.toResponse(snapshot, snapshot.zoneAt(ordinal));
        } catch (com.polyzones.service.persistence.JdbcZoneSetStore.IdempotencyConflictException conflict) {
            throw new ApplicationException("IDEMPOTENCY_CONFLICT", "The idempotency key was already used for another request.");
        } catch (com.polyzones.service.persistence.JdbcZoneSetStore.ZoneAlreadyExistsException conflict) {
            throw new ApplicationException("CONFLICT", "A zone with this identifier already exists.");
        } catch (ZoneSetPublisher.ZoneEditValidationException invalid) {
            throw new ApplicationException("VALIDATION_FAILED", "The zone edit is invalid.");
        } catch (ZoneSetPublisher.PublicationTimeoutException timeout) {
            throw new ApplicationException("ZONE_SET_UNAVAILABLE", "Zone publication capacity is temporarily unavailable.");
        } catch (ZoneSetPublisher.PublishFailedException failure) {
            if (failure.getCause() instanceof com.polyzones.service.persistence.JdbcZoneSetStore.ZoneAlreadyExistsException)
                throw new ApplicationException("CONFLICT", "A zone with this identifier already exists.");
            throw new ApplicationException("PUBLISH_FAILED", "The zone change could not be published.");
        }
    }

    private ZoneSnapshot latest(UUID tenant) {
        try { return publisherSnapshot(tenant); }
        catch (IllegalStateException missing) { throw notFound(); }
    }
    private PolygonShape validatedShape(ZoneEditRequest request) {
        List<ValidationIssueResponse> issues = validate(request);
        if (!issues.isEmpty()) throw new ApplicationException(issues.getFirst().code(), "The zone geometry failed validation.");
        return mapper.toShape(request.geometry());
    }
    private ZoneSnapshot publisherSnapshot(UUID tenant) { return snapshots.latest(tenant); }
    private static ApplicationException notFound() { return new ApplicationException("ZONE_NOT_FOUND", "The requested zone was not found."); }
    private String hash(ZoneEditRequest request, UUID zoneId, PolygonShape shape) {
        String canonical = zoneId + "|" + request.type() + "|" + request.priority() + "|" + mapper.canonicalGeometry(shape);
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}
