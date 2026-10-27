package com.example.webhook.worker;

import com.example.webhook.queue.EventQueue;
import com.example.webhook.service.DeliveryService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final EventQueue queue;
    private final DeliveryService delivery;
    private final int workerCount;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running = true;

    public DeliveryWorker(EventQueue queue, DeliveryService delivery,
                          @Value("${webhook.workers:4}") int workerCount) {
        this.queue = queue;
        this.delivery = delivery;
        this.workerCount = workerCount;
    }

    @PostConstruct
    void start() {
        for (int i = 1; i <= workerCount; i++) {
            Thread t = new Thread(this::loop, "delivery-worker-" + i);
            t.start();
            threads.add(t);
        }
        log.info("Started {} delivery workers", workerCount);
    }

    private void loop() {
        while (running) {
            try {
                Long id = queue.poll();
                if (id != null) delivery.deliver(id);
            } catch (Exception ex) {
                if (!running) return;                       // we are shutting down
                log.error("worker error, backing off 1s", ex);
                try {
                    Thread.sleep(1_000);                    // do not spin if Redis or the DB is down
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
    }
}