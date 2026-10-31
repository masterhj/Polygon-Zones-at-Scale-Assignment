package com.polyzones.service.web;

import com.polyzones.service.application.AlertService;
import com.polyzones.service.web.dto.AlertResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {
    private final AlertService alerts;
    public AlertController(AlertService alerts) { this.alerts = alerts; }
    @GetMapping
    public List<AlertResponse> list(@RequestParam(required = false) UUID tripId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return alerts.list(tripId, from, to, page, size);
    }
    @PostMapping("/{alertId}/ack")
    public AlertResponse acknowledge(@PathVariable UUID alertId) { return alerts.acknowledge(alertId); }
}
