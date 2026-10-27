package com.example.webhook.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class CircuitBreaker {
    static final int FAILURE_THRESHOLD = 5;                      // failures in a row before opening
    static final Duration COOLDOWN = Duration.ofSeconds(30);     // how long we stay open

    private final StringRedisTemplate redis;

    public CircuitBreaker(StringRedisTemplate redis) { this.redis = redis; }

    private String failKey(Long id)  { return "webhook:cb:" + id + ":fails"; }
    private String openKey(Long id)  { return "webhook:cb:" + id + ":open_until"; }
    private String probeKey(Long id) { return "webhook:cb:" + id + ":probe"; }

    /** 0 = go ahead. Otherwise: how many ms to wait before looking again. */
    public long waitMs(Long endpointId) {
        String openUntil = redis.opsForValue().get(openKey(endpointId));
        if (openUntil == null) return 0;                                   // CLOSED

        long remaining = Long.parseLong(openUntil) - System.currentTimeMillis();
        if (remaining > 0) return remaining;                               // OPEN

        // cool-down is over: HALF-OPEN. Exactly one caller may probe.
        Boolean won = redis.opsForValue().setIfAbsent(probeKey(endpointId), "1", Duration.ofSeconds(30));
        return Boolean.TRUE.equals(won) ? 0 : 5_000;
    }

    public void recordSuccess(Long endpointId) {
        redis.delete(List.of(failKey(endpointId), openKey(endpointId), probeKey(endpointId)));
    }

    public void recordFailure(Long endpointId) {
        Long fails = redis.opsForValue().increment(failKey(endpointId));
        redis.expire(failKey(endpointId), Duration.ofHours(1));            // forget very old failures
        redis.delete(probeKey(endpointId));                                // the probe (if any) is finished
        if (fails != null && fails >= FAILURE_THRESHOLD) {
            redis.opsForValue().set(openKey(endpointId),
                    String.valueOf(System.currentTimeMillis() + COOLDOWN.toMillis()));
        }
    }

    public Map<String, Object> state(Long endpointId) {
        String fails = redis.opsForValue().get(failKey(endpointId));
        String openUntil = redis.opsForValue().get(openKey(endpointId));
        String state = "CLOSED";
        if (openUntil != null) {
            state = Long.parseLong(openUntil) > System.currentTimeMillis() ? "OPEN" : "HALF_OPEN";
        }
        return Map.of("state", state,
                "consecutiveFailures", fails == null ? 0 : Integer.parseInt(fails));
    }
}