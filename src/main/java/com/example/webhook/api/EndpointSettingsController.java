package com.example.webhook.api;

import com.example.webhook.domain.Endpoint;
import com.example.webhook.repo.EndpointRepository;
import com.example.webhook.service.CircuitBreaker;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/endpoints/{id}")
public class EndpointSettingsController {
    private final EndpointRepository endpoints;
    private final CircuitBreaker breaker;

    public EndpointSettingsController(EndpointRepository endpoints, CircuitBreaker breaker) {
        this.endpoints = endpoints;
        this.breaker = breaker;
    }

    @PutMapping("/rate-limit")
    public Map<String, Object> setRateLimit(@PathVariable Long id, @RequestParam int perSecond) {
        if (perSecond < 1 || perSecond > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "perSecond must be 1 to 1000");
        }
        Endpoint e = endpoints.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint not found"));
        e.setRateLimitPerSecond(perSecond);
        endpoints.save(e);
        return Map.of("id", id, "rateLimitPerSecond", perSecond);
    }

    @GetMapping("/health")
    public Map<String, Object> health(@PathVariable Long id) {
        if (!endpoints.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint not found");
        }
        return breaker.state(id);
    }
}