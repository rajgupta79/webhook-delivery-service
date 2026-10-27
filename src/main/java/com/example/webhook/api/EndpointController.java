package com.example.webhook.api;

import com.example.webhook.domain.Endpoint;
import com.example.webhook.repo.EndpointRepository;
import com.example.webhook.service.SecretGenerator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/endpoints")
public class EndpointController {

    private final EndpointRepository repo;

    public EndpointController(EndpointRepository repo) { this.repo = repo; }

    public record CreateEndpoint(@NotBlank String url) {}

    @PostMapping
    public Endpoint create(@Valid @RequestBody CreateEndpoint req) {
        var e = new Endpoint();
        e.setUrl(req.url());
        e.setSecret(SecretGenerator.newSecret());
        return repo.save(e);
    }

    @PostMapping("/{id}/rotate-secret")
    public Map<String, Object> rotate(@PathVariable Long id,
                                      @RequestParam(defaultValue = "86400") long graceSeconds) {
        Endpoint e = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint not found"));
        long grace = Math.max(0, Math.min(graceSeconds, 7 * 24 * 3600));   // 0 to 7 days

        e.setPreviousSecret(e.getSecret());
        e.setPreviousSecretExpiresAt(Instant.now().plusSeconds(grace));
        e.setSecret(SecretGenerator.newSecret());
        repo.save(e);

        return Map.of("id", e.getId(),
                "secret", e.getSecret(),
                "previousSecretExpiresAt", e.getPreviousSecretExpiresAt().toString());
    }
}