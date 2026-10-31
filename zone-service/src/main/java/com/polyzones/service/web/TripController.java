package com.polyzones.service.web;

import com.polyzones.service.application.TripService;
import com.polyzones.service.web.dto.PingAdmissionResponse;
import com.polyzones.service.web.dto.PingRequest;
import com.polyzones.service.web.dto.TripAttributionResponse;
import com.polyzones.service.web.dto.TripEventResponse;
import com.polyzones.service.web.dto.TripResponse;
import com.polyzones.service.web.dto.TripStartRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/trips")
public class TripController {
    private final TripService trips;
    public TripController(TripService trips) { this.trips = trips; }

    @PostMapping
    public ResponseEntity<TripResponse> start(@Valid @RequestBody TripStartRequest request) {
        TripResponse response = trips.start(request);
        return ResponseEntity.created(URI.create("/api/v1/trips/" + response.tripId())).body(response);
    }
    @PostMapping("/{tripId}/pings")
    public PingAdmissionResponse pings(@PathVariable UUID tripId, @Valid @RequestBody PingRequest request) { return trips.pings(tripId, request); }
    @PostMapping("/{tripId}/complete")
    public TripResponse complete(@PathVariable UUID tripId) { return trips.complete(tripId); }
    @GetMapping("/{tripId}")
    public TripResponse get(@PathVariable UUID tripId) { return trips.get(tripId); }
    @GetMapping("/{tripId}/attribution")
    public TripAttributionResponse attribution(@PathVariable UUID tripId) { return trips.attribution(tripId); }
    @GetMapping("/{tripId}/events")
    public List<TripEventResponse> events(@PathVariable UUID tripId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) { return trips.events(tripId, page, size); }
}
