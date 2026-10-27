package com.example.webhook.service;

import com.example.webhook.domain.DeliveryAttempt;
import com.example.webhook.domain.Endpoint;
import com.example.webhook.domain.Event;
import com.example.webhook.metrics.WebhookMetrics;
import com.example.webhook.queue.EventQueue;
import com.example.webhook.repo.DeliveryAttemptRepository;
import com.example.webhook.repo.EndpointRepository;
import com.example.webhook.repo.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class DeliveryService {
    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);
    static final int MAX_ATTEMPTS = 6;
    private static final int DEFAULT_RATE_PER_SECOND = 5;
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final Duration MAX_EVENT_AGE = Duration.ofHours(24);

    private final EventRepository events;
    private final EndpointRepository endpoints;
    private final DeliveryAttemptRepository attemptLog;
    private final EventQueue queue;
    private final RateLimiter rateLimiter;
    private final CircuitBreaker breaker;
    private final WebhookMetrics metrics;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public DeliveryService(EventRepository events, EndpointRepository endpoints,
                           DeliveryAttemptRepository attemptLog, EventQueue queue,
                           RateLimiter rateLimiter, CircuitBreaker breaker,
                           WebhookMetrics metrics) {
        this.events = events;
        this.endpoints = endpoints;
        this.attemptLog = attemptLog;
        this.queue = queue;
        this.rateLimiter = rateLimiter;
        this.breaker = breaker;
        this.metrics = metrics;
    }

    /** Puts eventId into every log line made by this thread, then does the real work. */
    public void deliver(Long eventId) {
        MDC.put("eventId", String.valueOf(eventId));
        try {
            process(eventId);
        } finally {
            MDC.clear();     // worker threads are reused, so always clean up
        }
    }

    private void process(Long eventId) {
        // ---- 1. A cheap look. No lock yet. ----
        Event peek = events.findById(eventId).orElse(null);
        if (peek == null || !"PENDING".equals(peek.getStatus())) return;
        MDC.put("endpointId", String.valueOf(peek.getEndpointId()));

        Endpoint ep = endpoints.findById(peek.getEndpointId()).orElse(null);
        if (ep == null) {
            kill(peek, "endpoint_missing", "Endpoint " + peek.getEndpointId() + " not found");
            return;
        }
        if (Duration.between(peek.getCreatedAt(), Instant.now()).compareTo(MAX_EVENT_AGE) > 0) {
            kill(peek, "too_old", "Not delivered within 24 hours");
            return;
        }

        // ---- 2. Be polite: rate limit, then circuit breaker ----
        int limit = ep.getRateLimitPerSecond() != null ? ep.getRateLimitPerSecond() : DEFAULT_RATE_PER_SECOND;
        if (!rateLimiter.tryAcquire(ep.getId(), limit)) {
            metrics.deferred("rate_limit");
            defer(eventId, 1_000);
            return;
        }
        long waitMs = breaker.waitMs(ep.getId());
        if (waitMs > 0) {
            metrics.deferred("circuit_open");
            defer(eventId, waitMs);
            return;
        }

        // ---- 3. Claim it. Only one worker can win. ----
        if (events.claim(eventId, Instant.now().plus(LEASE)) == 0) return;
        Event ev = events.findById(eventId).orElseThrow();

        // ---- 4. Send ----
        int attemptNo = ev.getAttempts() + 1;
        Integer code = null;
        String error = null;
        long start = System.nanoTime();

        try {
            long ts = Instant.now().getEpochSecond();
            HttpRequest req = HttpRequest.newBuilder(URI.create(ep.getUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Event-Id", ev.getId().toString())
                    .header("X-Webhook-Signature", Signer.header(ts, ev.getPayload(), secretsFor(ep)))
                    .POST(HttpRequest.BodyPublishers.ofString(ev.getPayload()))
                    .build();
            code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException ie) {
            release(ev);
            Thread.currentThread().interrupt();
            return;
        } catch (Exception ex) {
            error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }

        long ms = (System.nanoTime() - start) / 1_000_000;
        String outcome = code != null ? "HTTP " + code : error;

        DeliveryAttempt a = new DeliveryAttempt();
        a.setEventId(ev.getId());
        a.setAttemptNumber(attemptNo);
        a.setStatusCode(code);
        a.setError(error);
        a.setDurationMs(ms);
        attemptLog.save(a);
        log.info("attempt {} -> {} in {} ms", attemptNo, outcome, ms);

        String outcomeTag = code == null ? "network_error"
                : (code >= 200 && code < 300) ? "success"
                : isPermanent(code) ? "permanent_failure"
                : "retryable_http";
        metrics.attempt(outcomeTag, ms);

        // ---- 5. Tell the circuit breaker how the endpoint behaved ----
        boolean reachable = code != null && code < 500 && code != 408 && code != 429;
        if (reachable) breaker.recordSuccess(ep.getId());
        else breaker.recordFailure(ep.getId());

        // ---- 6. Decide what happens to the event ----
        ev.setAttempts(attemptNo);
        if (code != null && code >= 200 && code < 300) {
            finish(ev, "DELIVERED");
            if (ev.getReplayCount() == null || ev.getReplayCount() == 0) {   // replays would distort the numbers
                metrics.endToEnd(Duration.between(ev.getCreatedAt(), Instant.now()));
            }
        } else if (code != null && isPermanent(code)) {
            kill(ev, "permanent", "Permanent failure: " + outcome);
        } else if (attemptNo >= MAX_ATTEMPTS) {
            kill(ev, "retries_exhausted", "Gave up after " + attemptNo + " attempts. Last: " + outcome);
        } else {
            retryLater(ev, attemptNo);
        }
    }

    private static boolean isPermanent(int code) {
        return code >= 300 && code < 500 && code != 408 && code != 429;
    }

    private static List<String> secretsFor(Endpoint ep) {
        List<String> secrets = new ArrayList<>();
        secrets.add(ep.getSecret());
        if (ep.getPreviousSecret() != null
                && ep.getPreviousSecretExpiresAt() != null
                && ep.getPreviousSecretExpiresAt().isAfter(Instant.now())) {
            secrets.add(ep.getPreviousSecret());
        }
        return secrets;
    }

    private void defer(Long eventId, long delayMs) {
        long jitter = ThreadLocalRandom.current().nextLong(1_000);
        queue.schedule(eventId, Instant.now().plusMillis(delayMs + jitter));
    }

    private void retryLater(Event ev, int attemptNo) {
        Instant when = Instant.now().plusMillis(Backoff.delayMs(attemptNo));
        ev.setStatus("PENDING");
        ev.setLockedUntil(null);
        ev.setNextAttemptAt(when);
        events.save(ev);
        queue.schedule(ev.getId(), when);
        metrics.retryScheduled();
    }

    private void finish(Event ev, String status) {
        ev.setStatus(status);
        ev.setLockedUntil(null);
        events.save(ev);
    }

    private void kill(Event ev, String kind, String reason) {
        ev.setStatus("DEAD");
        ev.setLockedUntil(null);
        ev.setDeadReason(reason);
        ev.setDeadAt(Instant.now());
        events.save(ev);
        metrics.dead(kind);
        log.warn("event is DEAD: {}", reason);
    }

    private void release(Event ev) {
        ev.setStatus("PENDING");
        ev.setLockedUntil(null);
        events.save(ev);
    }
}