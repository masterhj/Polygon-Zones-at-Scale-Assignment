package com.polyzones.core.trip;

import java.util.List;

public record TripCompletion(TripSummary summary, List<ZoneEvent> exitEvents) {
    public TripCompletion { exitEvents = List.copyOf(exitEvents); }
}
