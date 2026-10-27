package com.example.webhook.api;

import com.example.webhook.service.SignatureVerifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/test/signed")
public class SignedReceiverController {

    private final AtomicReference<String> secret = new AtomicReference<>("");

    @PostMapping("/secret")                      // tell the fake customer which secret it knows
    public String setSecret(@RequestBody String s) {
        secret.set(s.trim());
        return "ok";
    }

    @PostMapping                                 // receive webhooks and verify them
    public ResponseEntity<String> receive(
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        boolean ok = SignatureVerifier.verify(signature, rawBody, secret.get(),
                300, Instant.now().getEpochSecond());
        return ok ? ResponseEntity.ok("verified")
                : ResponseEntity.status(401).body("bad signature");
    }
}