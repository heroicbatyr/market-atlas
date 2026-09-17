package com.batyrbek.finance.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp, int status, String error, String code, String message, String path,
        String resolution, Instant retryAfter
) {
    public ApiError(Instant timestamp, int status, String error, String code, String message, String path) {
        this(timestamp, status, error, code, message, path, null, null);
    }
}
