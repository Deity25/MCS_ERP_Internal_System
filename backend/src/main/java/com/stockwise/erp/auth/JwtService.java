package com.stockwise.erp.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

@Service
public class JwtService {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final byte[] secret;
    private final long ttlMinutes;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.ttl-minutes:480}") long ttlMinutes) {
        if (secret.length() < 32) throw new IllegalArgumentException("JWT_SECRET must contain at least 32 characters");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlMinutes = ttlMinutes;
    }

    public String issue(ErpUser user) {
        long expiresAt = Instant.now().plusSeconds(ttlMinutes * 60).getEpochSecond();
        String payload = user.id() + "\n" + user.organizationId() + "\n" + user.fullName() + "\n" + user.email() + "\n" + user.role() + "\n" + expiresAt;
        String encoded = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + ENCODER.encodeToString(sign(encoded));
    }

    public ErpUser verify(String token) {
        try {
            String[] chunks = token.split("\\.", 2);
            if (chunks.length != 2 || !java.security.MessageDigest.isEqual(sign(chunks[0]), DECODER.decode(chunks[1]))) return null;
            String[] fields = new String(DECODER.decode(chunks[0]), StandardCharsets.UTF_8).split("\n", -1);
            if (fields.length != 6 || Long.parseLong(fields[5]) < Instant.now().getEpochSecond()) return null;
            return new ErpUser(Long.parseLong(fields[0]), Long.parseLong(fields[1]), fields[2], fields[3], fields[4]);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to sign access token", ex);
        }
    }
}
