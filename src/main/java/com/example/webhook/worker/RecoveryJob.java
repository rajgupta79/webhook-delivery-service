package com.example.webhook.worker;

import com.example.webhook.metrics.WebhookMetrics;
import com.example.webhook.queue.EventQueue;
import com.example.webhook.service.RecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RecoveryJob {
    private static final Logger log = LoggerFactory.getLogger(RecoveryJob.class);
    private final RecoveryService recovery;
    private final EventQueue queue;
    private final WebhookMetrics metrics;

    public RecoveryJob(RecoveryService recovery, EventQueue queue, WebhookMetrics metrics) {
        this.recovery = recovery;
        this.queue = queue;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelay = 30_000)
    public void requeueStuckEvents() {
        List<Long> ids = recovery.claimStuck();
        int requeued = 0;
        for (Long id : ids) {
            if (!queue.isScheduled(id)) {
                queue.enqueue(id);
                requeued++;
            }
        }
        if (requeued > 0) {
            metrics.recovered(requeued);
            log.warn("Re-queued {} stuck events", requeued);
        }
    }
}