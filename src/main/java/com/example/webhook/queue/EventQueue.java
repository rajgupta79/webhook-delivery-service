package com.example.webhook.queue;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@Component
public class EventQueue {
    private static final String KEY = "webhook:queue";
    private final StringRedisTemplate redis;

    public EventQueue(StringRedisTemplate redis) { this.redis = redis; }

    public void enqueue(Long eventId) {
        redis.opsForList().leftPush(KEY, eventId.toString());
    }

    /** Blocks up to 5s. Returns null if nothing arrived. */
    public Long poll() {
        String v = redis.opsForList().rightPop(KEY, Duration.ofSeconds(5));
        return v == null ? null : Long.valueOf(v);
    }

    private static final String DELAYED = "webhook:delayed";

    public void schedule(Long eventId, Instant when) {
        redis.opsForZSet().add(DELAYED, eventId.toString(), when.toEpochMilli());
    }

    /** Moves every due ID from the delayed set to the main queue. */
    public int promoteDue() {
        Set<String> due = redis.opsForZSet().rangeByScore(DELAYED, 0, System.currentTimeMillis());
        if (due == null) return 0;
        int moved = 0;
        for (String id : due) {
            Long removed = redis.opsForZSet().remove(DELAYED, id);
            if (removed != null && removed > 0) {      // only the one who removed it enqueues it
                redis.opsForList().leftPush(KEY, id);
                moved++;
            }
        }
        return moved;
    }
    public boolean isScheduled(Long eventId) {
        return redis.opsForZSet().score(DELAYED, eventId.toString()) != null;
    }

    public long depth() {
        Long n = redis.opsForList().size(KEY);
        return n == null ? 0 : n;
    }

    public long delayedSize() {
        Long n = redis.opsForZSet().size(DELAYED);
        return n == null ? 0 : n;
    }
}