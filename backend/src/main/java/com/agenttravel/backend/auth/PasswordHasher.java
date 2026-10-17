package com.agenttravel.backend.auth;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class PasswordHasher {

    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private final SecureRandom random = new SecureRandom();

    public String encode(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(derived);
    }

    public boolean matches(String password, String encoded) {
        try {
            String[] parts = encoded.split("\\$", 4);
            if (parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])) {
                return false;
            }
            byte[] actual = derive(password, Base64.getDecoder().decode(parts[2]), Integer.parseInt(parts[1]));
            return MessageDigest.isEqual(actual, Base64.getDecoder().decode(parts[3]));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private byte[] derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
