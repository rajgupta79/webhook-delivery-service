package com.example.webhook.service;

import com.example.webhook.domain.Event;
import com.example.webhook.metrics.WebhookMetrics;
import com.example.webhook.queue.EventQueue;
import com.example.webhook.repo.EndpointRepository;
import com.example.webhook.repo.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IngestService {
    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final EndpointRepository endpoints;
    private final EventRepository events;
    private final EventQueue queue;
    private final WebhookMetrics metrics;

    public IngestService(EndpointRepository endpoints, EventRepository events,
                         EventQueue queue, WebhookMetrics metrics) {
        this.endpoints = endpoints;
        this.events = events;
        this.queue = queue;
        this.metrics = metrics;
    }

    public Long ingest(Long endpointId, String key, String payload) {
        if (!endpoints.existsById(endpointId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown endpoint");
        }
        var existing = events.findByEndpointIdAndIdempotencyKey(endpointId, key);
        if (existing.isPresent()) {
            metrics.duplicate();
            return existing.get().getId();
        }

        Event saved;
        try {
            var e = new Event();
            e.setEndpointId(endpointId);
            e.setIdempotencyKey(key);
            e.setPayload(payload);
            saved = events.saveAndFlush(e);
        } catch (DataIntegrityViolationException race) {
            metrics.duplicate();
            return events.findByEndpointIdAndIdempotencyKey(endpointId, key).orElseThrow().getId();
        }

        metrics.ingested();
        try {
            queue.enqueue(saved.getId());
        } catch (Exception redisDown) {
            // The event is safely in Postgres, so we still accept it.
            // The recovery job will queue it once Redis is back.
            metrics.enqueueFailed();
            log.warn("Redis enqueue failed for event {}; recovery job will pick it up", saved.getId(), redisDown);
        }
        return saved.getId();
    }
}