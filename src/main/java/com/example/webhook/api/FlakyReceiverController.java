package com.example.webhook.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/test")
public class FlakyReceiverController {
    private final AtomicInteger calls = new AtomicInteger();

    @PostMapping("/flaky")
    public ResponseEntity<String> flaky(@RequestBody String body) {
        int n = calls.incrementAndGet();
        if (n % 4 != 0) return ResponseEntity.status(500).body("fail " + n);
        return ResponseEntity.ok("ok " + n);
    }

    private final AtomicBoolean failing = new AtomicBoolean(true);

    @PostMapping("/switch")
    public ResponseEntity<String> receive(@RequestBody String body) {
        return failing.get() ? ResponseEntity.status(503).body("down")
                : ResponseEntity.ok("up");
    }

    @PostMapping("/switch/{state}")          // state is "up" or "down"
    public String setState(@PathVariable String state) {
        failing.set(state.equals("down"));
        return "failing=" + failing.get();
    }
}