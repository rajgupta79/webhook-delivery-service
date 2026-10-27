package com.example.webhook.service;

import com.example.webhook.repo.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class RecoveryService {
    private final EventRepository events;

    public RecoveryService(EventRepository events) { this.events = events; }

    /** Locks up to 100 stuck events, resets them, and returns their IDs. */
    @Transactional
    public List<Long> claimStuck() {
        Instant now = Instant.now();
        List<Long> ids = events.lockStuck(now.minusSeconds(60), now, 100);
        if (!ids.isEmpty()) {
            events.resetAndBump(ids, now);
        }
        return ids;
    }
}