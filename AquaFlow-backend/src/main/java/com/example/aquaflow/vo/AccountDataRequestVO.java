package com.example.aquaflow.vo;

import java.time.LocalDateTime;

/** Only the person's request, never their credentials or a promise of completed processing. */
public record AccountDataRequestVO(Long id, String requestType, String requestTypeText, String note, String status,
        String statusText, LocalDateTime submittedAt, String notice) {}
