package com.polyzones.service.persistence.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class ZoneEventId implements Serializable {
    public UUID tenantId; public UUID eventId;
    public ZoneEventId() { }
    @Override public boolean equals(Object other) { return other instanceof ZoneEventId key && Objects.equals(tenantId, key.tenantId) && Objects.equals(eventId, key.eventId); }
    @Override public int hashCode() { return Objects.hash(tenantId, eventId); }
}
