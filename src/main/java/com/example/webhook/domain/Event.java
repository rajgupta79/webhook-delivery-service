package com.example.webhook.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Entity
@Data
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"endpointId", "idempotencyKey"}))
public class Event {
    @Id
    @GeneratedValue
    private Long id;
    private Long endpointId;
    private String idempotencyKey;   // sender-provided, dedupes ingest
    @Column(columnDefinition = "text") private String payload;
    private String status = "PENDING";
    private Instant createdAt = Instant.now();
    private Integer attempts = 0;
    private Instant nextAttemptAt = Instant.now();
    @Column(columnDefinition = "text")
    private String deadReason;
    private Instant deadAt;
    private Integer replayCount = 0;
    private Instant lockedUntil;
    // getters/setters
}