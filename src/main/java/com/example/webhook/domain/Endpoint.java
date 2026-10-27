package com.example.webhook.domain;


import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

import java.time.Instant;


@Entity
@Data
public class Endpoint {
    @Id
    @GeneratedValue
    private Long id;
    private String url;
    private String secret;      // used for HMAC signing later
    private boolean active = true;
    private String previousSecret;
    private Instant previousSecretExpiresAt;
    private Integer rateLimitPerSecond;
    // getters/setters
}