package com.polyzones.core.trip;

import java.util.List;

public record ProcessResult(long processedSeq, List<ZoneEvent> events, List<AlertCandidate> alerts,
                            boolean suspectJump, boolean timeRegression) {
    public ProcessResult { events = List.copyOf(events); alerts = List.copyOf(alerts); }
}
