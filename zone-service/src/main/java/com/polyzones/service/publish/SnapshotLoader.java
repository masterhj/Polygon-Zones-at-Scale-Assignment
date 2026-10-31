package com.polyzones.service.publish;

import com.polyzones.core.index.ZoneSnapshot;
import java.util.UUID;

/** Durable snapshot reconstruction. Implementations must filter ownership and publication status. */
public interface SnapshotLoader {
    ZoneSnapshot loadPublished(UUID tenantId, long setVersion);

    /** Publisher-only load that may include only its own BUILDING set. */
    ZoneSnapshot loadCandidate(UUID tenantId, long buildingSetVersion);
}
