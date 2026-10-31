package com.polyzones.service.web.dto;

public record LoginResponse(String accessToken, long expiresInSeconds, String tokenType) {
    @Override public String toString() { return "LoginResponse[accessToken=<redacted>, expiresInSeconds=" + expiresInSeconds + ", tokenType=" + tokenType + "]"; }
}
