package com.example.webhook.metrics;

import com.example.webhook.queue.EventQueue;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class WebhookMetrics {
    private final MeterRegistry registry;

    public WebhookMetrics(MeterRegistry registry, EventQueue queue) {
        this.registry = registry;
        Gauge.builder("webhook.queue.depth", () -> safe(queue::depth))
                .description("Event IDs waiting for a worker").register(registry);
        Gauge.builder("webhook.queue.delayed", () -> safe(queue::delayedSize))
                .description("Event IDs parked for retry, rate limit or open circuit").register(registry);
    }

    public void ingested()       { registry.counter("webhook.events.ingested").increment(); }
    public void duplicate()      { registry.counter("webhook.events.duplicates").increment(); }
    public void enqueueFailed()  { registry.counter("webhook.enqueue.failures").increment(); }
    public void retryScheduled() { registry.counter("webhook.retries.scheduled").increment(); }
    public void recovered(int n) { registry.counter("webhook.recovery.requeued").increment(n); }
    public void deferred(String reason) { registry.counter("webhook.delivery.deferred", "reason", reason).increment(); }
    public void dead(String reason)     { registry.counter("webhook.events.dead", "reason", reason).increment(); }

    /** One delivery attempt: how it ended and how long the HTTP call took. */
    public void attempt(String outcome, long millis) {
        registry.counter("webhook.delivery.attempts", "outcome", outcome).increment();
        Timer.builder("webhook.delivery.duration")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry)
                .record(millis, TimeUnit.MILLISECONDS);
    }

    /** From "we accepted it" to "customer got it". What the customer actually experiences. */
    public void endToEnd(Duration d) {
        Timer.builder("webhook.delivery.end_to_end")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(5))
                .maximumExpectedValue(Duration.ofMinutes(10))
                .register(registry)
                .record(d);
    }

    private static double safe(Supplier<Long> s) {
        try { return s.get(); } catch (Exception e) { return Double.NaN; }
    }
}