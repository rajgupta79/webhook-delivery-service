package com.example.webhook.api;

import com.example.webhook.domain.Event;
import com.example.webhook.queue.EventQueue;
import com.example.webhook.repo.EndpointRepository;
import com.example.webhook.repo.EventRepository;
import com.example.webhook.repo.StatusCount;
import com.example.webhook.service.CircuitBreaker;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final EventRepository events;
    private final EndpointRepository endpoints;
    private final EventQueue queue;
    private final CircuitBreaker breaker;

    public DashboardController(EventRepository events, EndpointRepository endpoints,
                               EventQueue queue, CircuitBreaker breaker) {
        this.events = events;
        this.endpoints = endpoints;
        this.queue = queue;
        this.breaker = breaker;
    }

    /** What the list shows. Deliberately has NO secret fields. */
    public record EndpointView(Long id, String url, boolean active,
                               Integer rateLimitPerSecond, Map<String, Object> circuit) {}

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (String s : List.of("PENDING", "DELIVERING", "DELIVERED", "DEAD")) byStatus.put(s, 0L);
        for (StatusCount c : events.countByStatus()) byStatus.put(c.getStatus(), c.getTotal());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byStatus", byStatus);
        out.put("queueDepth", safe(queue::depth));
        out.put("delayed", safe(queue::delayedSize));
        out.put("endpoints", endpoints.count());
        return out;
    }

    @GetMapping("/endpoints")
    public List<EndpointView> endpointList() {
        return endpoints.findAll(PageRequest.of(0, 100, Sort.by(Sort.Direction.DESC, "id")))
                .getContent().stream()
                .map(e -> new EndpointView(e.getId(), e.getUrl(), e.isActive(),
                        e.getRateLimitPerSecond(), circuit(e.getId())))
                .toList();
    }

    @GetMapping("/events")
    public List<Event> recentEvents(@RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "50") int limit) {
        var page = PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
        return (status == null || status.isBlank())
                ? events.findAllByOrderByIdDesc(page)
                : events.findByStatusOrderByIdDesc(status, page);
    }

    private Map<String, Object> circuit(Long endpointId) {
        try {
            return breaker.state(endpointId);
        } catch (Exception redisDown) {
            return Map.of("state", "UNKNOWN");
        }
    }

    /** -1 means "could not read" (for example Redis is down), so the page shows n/a. */
    private static long safe(Supplier<Long> s) {
        try { return s.get(); } catch (Exception e) { return -1; }
    }
}