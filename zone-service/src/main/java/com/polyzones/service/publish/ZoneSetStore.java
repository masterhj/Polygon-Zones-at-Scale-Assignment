package com.polyzones.service.publish;

import java.util.UUID;

/** Each method is a short, independently committed transaction. */
public interface ZoneSetStore {
    long beginBuild(UUID tenantId, ZoneEditCommand command);
    void markPublished(UUID tenantId, long setVersion);
    void markFailed(UUID tenantId, long setVersion);
    ZoneSetPublisher.PublishResult findIdempotent(UUID tenantId, String key, String bodyHash);
}
