package com.polyzones.service.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(@NotBlank @Size(max = 128) String tenant,
                           @NotBlank @Size(max = 128) String username,
                           @NotBlank @Size(max = 1024) String password) {
    @Override public String toString() { return "LoginRequest[credentials=<redacted>]"; }
}
