package com.example.webhook.service;

import java.util.ArrayList;
import java.util.List;

public final class SignatureVerifier {

    public static boolean verify(String header, String rawBody, String secret,
                                 long toleranceSeconds, long nowSeconds) {
        if (header == null || secret == null || secret.isEmpty()) return false;

        Long timestamp = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if (kv[0].equals("t")) {
                try { timestamp = Long.parseLong(kv[1]); }
                catch (NumberFormatException e) { return false; }
            } else if (kv[0].equals("v1")) {
                signatures.add(kv[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty()) return false;

        if (Math.abs(nowSeconds - timestamp) > toleranceSeconds) return false;   // too old or too new

        String expected = Signer.hmacSha256Hex(secret, timestamp + "." + rawBody);
        for (String sig : signatures) {
            if (Signer.constantTimeEquals(expected, sig)) return true;           // any v1 may match
        }
        return false;
    }
}