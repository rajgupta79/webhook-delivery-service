package com.example.webhook.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

public final class Signer {

    public static String hmacSha256Hex(String secret, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sig);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Builds: t=<timestamp>,v1=<sig>[,v1=<sig>...]  one v1 per secret. */
    public static String header(long timestamp, String payload, List<String> secrets) {
        StringBuilder sb = new StringBuilder("t=").append(timestamp);
        for (String secret : secrets) {
            sb.append(",v1=").append(hmacSha256Hex(secret, timestamp + "." + payload));
        }
        return sb.toString();
    }

    /** Compares without leaking, through timing, how many characters matched. */
    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}