package com.polyzones.service.publish;

import com.polyzones.core.zone.Zone;
import com.polyzones.core.zone.ZoneKey;
import java.util.List;

/** A set edit: upserts carry validated geometry and deletes become tombstones. */
public record ZoneEditCommand(List<Zone> upserts, List<ZoneKey> deletes, String idempotencyKey, String bodyHash,
                              boolean createOnly) {
    public ZoneEditCommand(List<Zone> upserts, List<ZoneKey> deletes, String idempotencyKey, String bodyHash) {
        this(upserts, deletes, idempotencyKey, bodyHash, false);
    }
    public ZoneEditCommand {
        upserts = List.copyOf(upserts);
        deletes = List.copyOf(deletes);
        if (idempotencyKey != null && idempotencyKey.isBlank()) throw new IllegalArgumentException("Invalid idempotency key");
        if (bodyHash != null && bodyHash.isBlank()) throw new IllegalArgumentException("Invalid body hash");
    }
}
