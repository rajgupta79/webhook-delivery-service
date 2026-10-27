package com.example.webhook.service;

import java.security.SecureRandom;
import java.util.Base64;

public final class SecretGenerator {
    private static final SecureRandom RNG = new SecureRandom();

    public static String newSecret() {
        byte[] bytes = new byte[32];                 // 256 bits of randomness
        RNG.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}