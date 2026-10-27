package com.example.webhook.api;

import com.example.webhook.domain.Event;
import com.example.webhook.queue.EventQueue;
import com.example.webhook.repo.EventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
public class DeadLetterController {

    private final EventRepository events;
    private final EventQueue queue;

    public DeadLetterController(EventRepository events, EventQueue queue) {
        this.events = events;
        this.queue = queue;
    }

    /** See what's dead: newest first, paged. */
    @GetMapping("/dead-letters")
    public List<Event> list(@RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size) {
        return events.findByStatusOrderByDeadAtDesc("DEAD", PageRequest.of(page, Math.min(size, 100)));
    }

    /** Replay one dead event. */
    @PostMapping("/events/{id}/replay")
    public ResponseEntity<Void> replay(@PathVariable Long id) {
        if (!events.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found");
        }
        if (events.revive(id, Instant.now()) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Event is not dead");
        }
        queue.enqueue(id);          // only AFTER the DB commit
        return ResponseEntity.accepted().build();
    }

    /** Replay up to 500 dead events for one customer endpoint. */
    @PostMapping("/endpoints/{endpointId}/replay-dead")
    public Map<String, Integer> replayAll(@PathVariable Long endpointId) {
        int count = 0;
        for (Event e : events.findTop500ByStatusAndEndpointIdOrderByIdAsc("DEAD", endpointId)) {
            if (events.revive(e.getId(), Instant.now()) > 0) {
                queue.enqueue(e.getId());
                count++;
            }
        }
        return Map.of("replayed", count);
    }
}