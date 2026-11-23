package com.agenttravel.backend.community;

import static com.agenttravel.backend.community.CommunityModels.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

import com.agenttravel.backend.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
class CommunitySafety {
    private static final int MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SIDE = 2048;
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private final List<String> blocked;

    CommunitySafety(@Value("${app.community.blocked-words:刷赞,代刷,刷单,赌博,毒品交易}") List<String> blocked) {
        this.blocked = blocked.stream().map(this::matchForm).filter(word -> !word.isEmpty()).toList();
    }

    String text(String value, int maxLength) {
        String cleaned = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        if (cleaned.length() > maxLength) {
            throw invalid("文本长度超限");
        }
        if (cleaned.isBlank() || cleaned.matches("(?s).*[<>&\\p{Cf}].*")
                || cleaned.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')
                || blocked.stream().anyMatch(matchForm(cleaned)::contains)) {
            throw invalid("请填写安全的纯文本");
        }
        return cleaned;
    }

    private String matchForm(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{P}\\p{Z}\\p{Cf}]", "");
    }

    Upload read(MultipartFile file, License license) {
        if (file.getSize() < 3 || file.getSize() > MAX_UPLOAD_BYTES) {
            throw invalid("图片大小不合法");
        }
        String source = license.sourceUrl();
        URI uri = null;
        if (source != null) {
            try {
                uri = URI.create(source);
            } catch (IllegalArgumentException exception) {
                throw invalid("图片来源链接无效");
            }
        }
        if ((uri == null && license.kind() != LicenseKind.OWN)
                || (uri != null && (uri.getHost() == null || uri.getUserInfo() != null
                        || !"https".equalsIgnoreCase(uri.getScheme())))) {
            throw invalid("图片须声明有效许可及来源");
        }
        try {
            byte[] bytes = file.getBytes();
            boolean png = Arrays.equals(PNG, Arrays.copyOf(bytes, PNG.length));
            boolean jpeg = bytes[0] == (byte) 255 && bytes[1] == (byte) 216 && bytes[2] == (byte) 255;
            String format = png ? "png" : "jpeg";
            if ((!png && !jpeg) || !("image/" + format).equalsIgnoreCase(file.getContentType())) {
                throw invalid("图片格式须为真实的 PNG 或 JPEG");
            }
            try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var reader = ImageIO.getImageReadersByFormatName(format).next();
                try {
                    reader.setInput(stream, true, true);
                    reader.addIIOReadWarningListener((sourceReader, warning) -> {
                        throw invalid("图片无法安全解码");
                    });
                    if (reader.getWidth(0) > MAX_SIDE || reader.getHeight(0) > MAX_SIDE) {
                        throw invalid("图片尺寸过大");
                    }
                    var pixels = reader.read(0);
                    var clean = new BufferedImage(pixels.getWidth(), pixels.getHeight(), BufferedImage.TYPE_INT_ARGB);
                    var graphics = clean.createGraphics();
                    try {
                        graphics.drawImage(pixels, 0, 0, null);
                    } finally {
                        graphics.dispose();
                    }
                    var output = new ByteArrayOutputStream();
                    ImageIO.write(clean, "png", output);
                    return new Upload(output.toByteArray(), license);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw invalid("图片无法安全解码");
        }
    }

    static byte[] digest(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", message);
    }

    record Upload(byte[] png, License license) {}
}
