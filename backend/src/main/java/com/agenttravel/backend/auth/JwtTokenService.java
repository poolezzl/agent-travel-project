package com.agenttravel.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.agenttravel.backend.common.ApiException;
import org.springframework.http.HttpStatus;

public class JwtTokenService {

    private final byte[] secret;
    private final long accessSeconds;

    public JwtTokenService(String secret, long accessSeconds) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("JWT_SECRET 至少需要 32 个字符");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.accessSeconds = accessSeconds;
    }

    public String createAccessToken(UUID userId, String role, long nowSeconds) {
        return createAccessToken(userId, role, null, nowSeconds);
    }

    public String createAccessToken(UUID userId, String role, UUID sessionId, long nowSeconds) {
        String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = encode("{\"sub\":\"" + userId + "\",\"role\":\"" + role
                + (sessionId == null ? "" : "\",\"sid\":\"" + sessionId)
                + "\",\"iat\":" + nowSeconds + ",\"exp\":" + (nowSeconds + accessSeconds) + "}");
        return header + "." + payload + "." + sign(header + "." + payload);
    }

    public Claims parseAccessToken(String token, long nowSeconds) {
        try {
            String[] pieces = token.split("\\.");
            if (pieces.length != 3 || !MessageDigest.isEqual(sign(pieces[0] + "." + pieces[1]).getBytes(StandardCharsets.US_ASCII),
                    pieces[2].getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalArgumentException();
            }
            String json = new String(Base64.getUrlDecoder().decode(pieces[1]), StandardCharsets.UTF_8);
            Map<String, String> values = json.substring(1, json.length() - 1).replace("\"", "")
                    .split(",") == null ? Map.of() : java.util.Arrays.stream(json.substring(1, json.length() - 1)
                            .replace("\"", "").split(","))
                            .map(pair -> pair.split(":", 2)).collect(java.util.stream.Collectors.toMap(pair -> pair[0], pair -> pair[1]));
            long exp = Long.parseLong(values.get("exp"));
            if (exp <= nowSeconds) {
                throw new IllegalArgumentException();
            }
            String session = values.get("sid");
            return new Claims(UUID.fromString(values.get("sub")), values.get("role"),
                    session == null ? null : UUID.fromString(session), exp);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "访问令牌无效或已过期");
        }
    }

    public String randomRefreshToken() {
        byte[] value = new byte[32];
        new java.security.SecureRandom().nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    public long accessSeconds() {
        return accessSeconds;
    }

    public byte[] hashRefreshToken(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record Claims(UUID userId, String role, UUID sessionId, long expiresAt) {
    }
}
