package com.example.webhook.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Entity
@Data
public class DeliveryAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long eventId;
    private int attemptNumber;
    private Integer statusCode;      // null if the request never got a response
    @Column(columnDefinition = "text")
    private String error;
    private long durationMs;
    private Instant createdAt = Instant.now();
}
