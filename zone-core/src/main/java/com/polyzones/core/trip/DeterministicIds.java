package com.polyzones.core.trip;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** UUIDv5 namespaces and canonical binary names for D-21 event/alert idempotency. */
public final class DeterministicIds {
    public static final UUID EVENT_NAMESPACE = UUID.fromString("93c96bc8-9e39-5b84-a9bd-3218b90086f4");
    public static final UUID ALERT_NAMESPACE = UUID.fromString("cf9b370f-97dd-5bdb-82ba-ec3f2b193f0e");
    private DeterministicIds() { }

    public static UUID eventId(UUID tenantId, UUID tripId, long transitionOrdinal) {
        if (tenantId == null || tripId == null || transitionOrdinal < 1) throw new IllegalArgumentException("Invalid event identity");
        ByteBuffer name = ByteBuffer.allocate(40).putLong(tenantId.getMostSignificantBits()).putLong(tenantId.getLeastSignificantBits())
                .putLong(tripId.getMostSignificantBits()).putLong(tripId.getLeastSignificantBits()).putLong(transitionOrdinal);
        return v5(EVENT_NAMESPACE, name.array());
    }

    public static UUID alertId(UUID sourceEventId, String kind) {
        if (sourceEventId == null || kind == null || kind.isBlank()) throw new IllegalArgumentException("Invalid alert identity");
        byte[] kindBytes = kind.getBytes(StandardCharsets.UTF_8);
        ByteBuffer name = ByteBuffer.allocate(18 + kindBytes.length).putLong(sourceEventId.getMostSignificantBits())
                .putLong(sourceEventId.getLeastSignificantBits()).putShort((short) kindBytes.length).put(kindBytes);
        return v5(ALERT_NAMESPACE, name.array());
    }

    private static UUID v5(UUID namespace, byte[] name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(ByteBuffer.allocate(16).putLong(namespace.getMostSignificantBits()).putLong(namespace.getLeastSignificantBits()).array());
            byte[] hash = sha1.digest(name);
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer bytes = ByteBuffer.wrap(hash);
            return new UUID(bytes.getLong(), bytes.getLong());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-1 unavailable", impossible); }
    }
}
