package com.example.webhook.worker;

import com.example.webhook.queue.EventQueue;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RetryPromoter {
    private final EventQueue queue;

    public RetryPromoter(EventQueue queue) { this.queue = queue; }

    @Scheduled(fixedDelay = 1000)
    public void promote() {
        queue.promoteDue();
    }
}