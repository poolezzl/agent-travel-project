package com.agenttravel.backend.community;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
class CommunityImages {
    private final Path directory;
    private final SecretKeySpec signingKey;

    CommunityImages(@Value("${app.community.object-directory:${user.home}/.agent-travel/community-images}") String directory,
            @Value("${app.security.jwt-secret}") String secret) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        signingKey = new SecretKeySpec(CommunitySafety.digest("community-image-v1:" + secret), "HmacSHA256");
    }

    String store(byte[] bytes) {
        String key = UUID.randomUUID() + ".png";
        Path file = directory.resolve(key);
        try {
            Files.createDirectories(directory);
            try (var output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                // 写入失败时也由同一事务清理文件。
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            remove(file);
                        }
                    }
                });
                output.write(bytes);
            }
            return key;
        } catch (IOException exception) {
            throw new IllegalStateException("图片存储不可用", exception);
        }
    }

    private void remove(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            org.slf4j.LoggerFactory.getLogger(CommunityImages.class).warn("社区图片回滚清理失败");
        }
    }

    byte[] load(String key) {
        if (!key.matches("[a-f0-9-]{36}\\.png")) {
            throw new IllegalStateException("图片对象标识不合法");
        }
        try {
            return Files.readAllBytes(directory.resolve(key));
        } catch (java.nio.file.NoSuchFileException exception) {
            throw CommunityService.missing();
        } catch (IOException exception) {
            throw new IllegalStateException("图片存储不可用", exception);
        }
    }

    String link(UUID post, UUID object) {
        long expires = Instant.now().plusSeconds(300).getEpochSecond();
        return "/api/v1/community/posts/" + post + "/images/" + object
                + "?expires=" + expires + "&signature=" + signature(post, object, expires);
    }

    boolean authorized(UUID post, UUID object, long expires, String signature) {
        return expires > Instant.now().getEpochSecond() && signature.length() == 43
                && MessageDigest.isEqual(signature(post, object, expires).getBytes(StandardCharsets.US_ASCII),
                        signature.getBytes(StandardCharsets.US_ASCII));
    }

    private String signature(UUID post, UUID object, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey);
            byte[] bytes = mac.doFinal((post + ":" + object + ":" + expires).getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
