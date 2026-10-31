package com.polyzones.service.web;

import com.polyzones.service.application.ZoneAdminService;
import com.polyzones.service.web.dto.ZoneEditRequest;
import com.polyzones.service.web.dto.ZoneResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/zones")
public final class ZoneAdminController {
    private final ZoneAdminService zones;
    public ZoneAdminController(ZoneAdminService zones) { this.zones = zones; }

    @PostMapping("/validate")
    public List<com.polyzones.service.web.dto.ValidationIssueResponse> validate(@Valid @RequestBody ZoneEditRequest request) {
        return zones.validate(request);
    }

    @PostMapping
    public ResponseEntity<ZoneResponse> create(@Valid @RequestBody ZoneEditRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        ZoneResponse response = zones.create(request, idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/zones/" + response.zoneId())).body(response);
    }

    @PutMapping("/{zoneId}")
    public ZoneResponse update(@PathVariable UUID zoneId, @Valid @RequestBody ZoneEditRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return zones.update(zoneId, request, idempotencyKey);
    }

    @DeleteMapping("/{zoneId}")
    public ResponseEntity<Void> delete(@PathVariable UUID zoneId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        zones.delete(zoneId, idempotencyKey);
        return ResponseEntity.noContent().build();
    }
}
