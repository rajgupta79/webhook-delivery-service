package com.example.webhook.service;

import java.util.concurrent.ThreadLocalRandom;

public final class Backoff {
    private static final long BASE_MS = 2_000;        // first retry window: 2s
    private static final long CAP_MS  = 5 * 60_000;   // never wait more than 5 min

    /** attempt = number of the attempt that just failed (1, 2, 3...) */
    public static long delayMs(int attempt) {
        long window = Math.min(CAP_MS, BASE_MS * (1L << Math.min(attempt - 1, 20)));
        return ThreadLocalRandom.current().nextLong(window + 1);   // random 0..window
    }
}