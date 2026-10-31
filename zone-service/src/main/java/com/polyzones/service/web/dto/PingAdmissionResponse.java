package com.polyzones.service.web.dto;

import java.util.List;

public record PingAdmissionResponse(List<Item> items, long durableAckedSeq) {
    public PingAdmissionResponse { items = List.copyOf(items); }
    public record Item(long seq, String status, String reason) { }
}
