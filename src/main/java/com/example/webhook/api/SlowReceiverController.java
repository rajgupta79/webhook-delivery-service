package com.example.webhook.api;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/test")
public class SlowReceiverController {

    @PostMapping("/ok")
    public String ok(@RequestBody String body) { return "ok"; }

    @PostMapping("/slow")
    public String slow(@RequestBody String body) throws InterruptedException {
        Thread.sleep(2000);          // a customer that takes 2 seconds to answer
        return "ok";
    }
}