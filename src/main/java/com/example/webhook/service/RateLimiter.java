package com.example.webhook.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RateLimiter {
    private final StringRedisTemplate redis;

    public RateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    /** true = you may send now; false = this endpoint already used its quota this second. */
    public boolean tryAcquire(Long endpointId, int limitPerSecond) {
        String key = "webhook:rate:" + endpointId + ":" + (System.currentTimeMillis() / 1000);
        Long count = redis.opsForValue().increment(key);        // atomic +1
        if (count != null && count == 1) {
            redis.expire(key, Duration.ofSeconds(2));           // clean up after itself
        }
        return count != null && count <= limitPerSecond;
    }
}