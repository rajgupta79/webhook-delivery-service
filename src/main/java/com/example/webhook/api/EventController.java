package com.example.webhook.api;

import com.example.webhook.domain.DeliveryAttempt;
import com.example.webhook.domain.Event;
import com.example.webhook.repo.DeliveryAttemptRepository;
import com.example.webhook.repo.EventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventRepository events;
    private final DeliveryAttemptRepository attemptLog;

    public EventController(EventRepository events, DeliveryAttemptRepository attemptLog) {
        this.events = events;
        this.attemptLog = attemptLog;
    }

    @GetMapping("/{id}")
    public Event get(@PathVariable Long id) {
        return events.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
    }

    @GetMapping("/{id}/attempts")
    public List<DeliveryAttempt> attempts(@PathVariable Long id) {
        return attemptLog.findByEventIdOrderById(id);
    }
}