package com.example.webhook.api;



import com.example.webhook.service.IngestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/endpoints/{endpointId}/events")
public class IngestController {

    private final IngestService service;

    public IngestController(IngestService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Long> ingest(
            @PathVariable Long endpointId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody String payload) {
        Long eventId = service.ingest(endpointId, key, payload);
        return ResponseEntity.accepted().body(eventId);
    }
}