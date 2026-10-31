package com.polyzones.service.web;

import com.polyzones.service.application.ZoneQueryService;
import com.polyzones.service.web.dto.PointQueryRequest;
import com.polyzones.service.web.dto.PointQueryResponse;
import com.polyzones.service.web.dto.ZoneResponse;
import com.polyzones.service.web.dto.ZoneSetSummary;
import com.polyzones.service.web.dto.ZoneVersionSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1")
public class ZoneQueryController {
    private final ZoneQueryService zones;
    public ZoneQueryController(ZoneQueryService zones) { this.zones = zones; }

    @GetMapping("/zones")
    public List<ZoneResponse> list(@RequestParam(required = false) Long setVersion,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return zones.list(setVersion, page, size);
    }

    @GetMapping("/zones/{zoneId}")
    public ZoneResponse get(@PathVariable UUID zoneId, @RequestParam(required = false) Integer version) {
        return zones.get(zoneId, version);
    }

    @GetMapping("/zones/{zoneId}/versions")
    public List<ZoneVersionSummary> versions(@PathVariable UUID zoneId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return zones.versions(zoneId, page, size);
    }

    @GetMapping("/zone-sets")
    public List<ZoneSetSummary> sets(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return zones.sets(page, size);
    }

    @PostMapping("/zones/query")
    public PointQueryResponse query(@Valid @RequestBody PointQueryRequest request) {
        return zones.query(request.lat(), request.lon(), request.asOfSetVersion());
    }

    @PostMapping("/zones/query/batch")
    public List<PointQueryResponse> batch(@RequestBody List<@NotNull @Valid PointQueryRequest> points) {
        return zones.batch(points);
    }
}
