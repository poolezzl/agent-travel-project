package com.agenttravel.backend.traveltools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
class ExportFiles {
    static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final String KEY = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.(pdf|ics)";
    private final Path directory;

    ExportFiles(@Value("${app.s26.export-directory:${user.home}/.agent-travel/exports}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    String store(ExportRenderer.Format format, byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("导出文件大小超限");
        }
        String key = UUID.randomUUID() + "." + format.extension;
        Files.createDirectories(directory);
        Path path = file(key);
        try (var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)) {
            output.write(bytes);
        }
        return key;
    }

    byte[] read(String key, long size) throws IOException {
        if (size <= 0 || size > MAX_BYTES) {
            throw new IOException("导出文件大小无效");
        }
        try (var input = Files.newByteChannel(file(key), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer data = ByteBuffer.allocate((int) size);
            while (data.hasRemaining() && input.read(data) != -1) {
                // 文件完整后才允许下载，避免投递残缺附件。
            }
            if (data.hasRemaining() || input.size() != size) {
                throw new IOException("导出文件大小不符");
            }
            return data.array();
        }
    }

    void delete(String key) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(file(key));
        }
    }

    List<String> olderThan(Instant cutoff) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        requireDirectory();
        try (var entries = Files.list(directory)) {
            var files = entries.filter(path -> path.getFileName().toString().matches(KEY)).toList();
            var older = new java.util.ArrayList<String>();
            for (Path path : files) {
                try {
                    if (Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) {
                        older.add(path.getFileName().toString());
                    }
                } catch (java.nio.file.NoSuchFileException ignored) {
                    // 另一实例已清理。
                }
            }
            return older;
        }
    }

    private Path file(String key) throws IOException {
        if (key == null || !key.matches(KEY)) {
            throw new IOException("导出文件标识无效");
        }
        requireDirectory();
        return directory.resolve(key);
    }

    private void requireDirectory() throws IOException {
        if (!directory.equals(directory.toRealPath()) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("导出目录无效");
        }
    }
}
