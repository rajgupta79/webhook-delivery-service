package com.example.webhook.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SignatureVerifierTest {
    private static final String SECRET = "whsec_current";
    private static final String OLD_SECRET = "whsec_old";
    private static final String BODY = "{\"event\":\"x\"}";
    private static final long NOW = 1_760_000_000L;

    private String header(long ts, String... secrets) {
        return Signer.header(ts, BODY, List.of(secrets));
    }

    @Test void validSignaturePasses() {
        assertTrue(SignatureVerifier.verify(header(NOW, SECRET), BODY, SECRET, 300, NOW));
    }

    @Test void tamperedBodyFails() {
        assertFalse(SignatureVerifier.verify(header(NOW, SECRET), "{\"event\":\"HACKED\"}", SECRET, 300, NOW));
    }

    @Test void wrongSecretFails() {
        assertFalse(SignatureVerifier.verify(header(NOW, SECRET), BODY, "whsec_wrong", 300, NOW));
    }

    @Test void oldTimestampFails() {            // replay attack: valid signature, but 1000s old
        assertFalse(SignatureVerifier.verify(header(NOW - 1000, SECRET), BODY, SECRET, 300, NOW));
    }

    @Test void changingTimestampBreaksSignature() {   // attacker edits t= to look fresh
        String forged = header(NOW - 1000, SECRET).replace("t=" + (NOW - 1000), "t=" + NOW);
        assertFalse(SignatureVerifier.verify(forged, BODY, SECRET, 300, NOW));
    }

    @Test void duringRotationEitherSecretWorks() {
        String h = header(NOW, SECRET, OLD_SECRET);     // two v1 values
        assertTrue(SignatureVerifier.verify(h, BODY, SECRET, 300, NOW));
        assertTrue(SignatureVerifier.verify(h, BODY, OLD_SECRET, 300, NOW));
    }
}