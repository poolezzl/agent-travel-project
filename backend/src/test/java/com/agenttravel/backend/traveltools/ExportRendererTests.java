package com.agenttravel.backend.traveltools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportRendererTests {
    private static final Instant NOW = Instant.parse("2026-11-23T00:00:00Z");

    @Test
    void calendarPreservesStableEventIdsAndEscapesTextWithUtf8Folding() throws Exception {
        JsonNode plan = new ObjectMapper().readTree("""
                {"days":[{"date":"2026-11-23","segments":[
                  {"point_id":"p1","name":"中文,行程;反斜\\\\换行\\nBEGIN:VEVENT","district":"城区",
                    "start_time":"09:00","end_time":"10:00"},
                  {"point_id":"p2","name":"%s"}]},
                  {"date":"2026-11-24","segments":[]}]}
                """.formatted("中文".repeat(40)));
        ExportRenderer renderer = new ExportRenderer("", "Asia/Shanghai");
        String first = new String(renderer.render(ExportRenderer.Format.ICS, "北京", plan, "version", "one", NOW),
                StandardCharsets.UTF_8);
        String second = new String(renderer.render(ExportRenderer.Format.ICS, "北京", plan, "version", "two", NOW.plusSeconds(8)),
                StandardCharsets.UTF_8);
        assertThat(first.lines().filter(line -> line.startsWith("UID:")).toList())
                .containsExactlyElementsOf(second.lines().filter(line -> line.startsWith("UID:")).toList())
                .doesNotHaveDuplicates().hasSize(3);
        assertThat(first.replace("\r\n ", "")).contains(
                "DTSTART:20261123T010000Z", "DTEND:20261123T020000Z", "DTSTART;VALUE=DATE:20261123",
                "DTEND;VALUE=DATE:20261124", "DTSTART;VALUE=DATE:20261124", "DTEND;VALUE=DATE:20261125",
                "SUMMARY:中文\\,行程\\;反斜\\\\换行\\nBEGIN:VEVENT", "SUMMARY:" + "中文".repeat(40));
        assertThat(first.lines().filter("BEGIN:VEVENT"::equals).count()).isEqualTo(3);
        assertThat(first.lines()).allMatch(line -> line.getBytes(StandardCharsets.UTF_8).length <= 75);
    }

    @Test
    void chinesePdfEmbedsFontAndWatermarkWithoutLoadingUntrustedImages() throws Exception {
        String font = System.getenv().getOrDefault("S26_TEST_PDF_FONT", "");
        String windows = System.getenv("WINDIR");
        assumeTrue(!font.isBlank() || (windows != null && Files.isRegularFile(Path.of(windows, "Fonts", "simhei.ttf"))),
                "需要可嵌入的中文测试字体");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            var database = new ObjectMapper();
            JsonNode plan = database.readTree("""
                    {"notice":"需核对的示例","days":[{"date":"2026-11-23","weekday":"星期一","segments":[
                    {"point_id":"poi","name":"北京故宫","start_time":"09:00","end_time":"10:00",
                    "image":"http://127.0.0.1:%s/image"},{"point_id":"free","name":"自由活动"}],
                    "map_thumbnail":"file:///private.png","images":["data:image/png;base64,invalid"],
                    "weather":{"condition":"过期晴天","valid_until":"2026-11-22T00:00:00Z"},
                    "reservations":[{"point_id":"poi","reservation_required":true}]}]}
                    """.formatted(server.getAddress().getPort()));
            byte[] pdf = new ExportRenderer(font, "Asia/Shanghai").render(ExportRenderer.Format.PDF, "中文行程", plan,
                    "version", "watermark", NOW);
            try (var document = Loader.loadPDF(pdf)) {
                String content = new PDFTextStripper().getText(document);
                assertThat(content).contains("中文行程", "北京故宫", "私人行程", "watermark", "预约", "天气资料已过期")
                        .containsPattern("全天\\s+自由活动")
                        .doesNotContain("过期晴天", "private.png", "data:image");
                var resources = document.getPage(0).getResources();
                assertThat(resources.getFont(resources.getFontNames().iterator().next()).isEmbedded()).isTrue();
                assertThat(resources.getXObjectNames()).isEmpty();
            }
            assertThat(requests.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void filesystemReadsOnlyGeneratedKeysAndChecksSize(@TempDir Path root) throws Exception {
        Path directory = root.resolve("exports");
        var files = new ExportFiles(directory.toString());
        byte[] content = "附件".getBytes(StandardCharsets.UTF_8);
        String key = files.store(ExportRenderer.Format.ICS, content);
        assertThat(files.read(key, content.length)).isEqualTo(content);
        for (String path : List.of("../outside.ics", "/absolute.ics", "%2e%2e%2foutside.ics", key + ":payload")) {
            assertThatThrownBy(() -> files.read(path, content.length)).isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> files.read(key, content.length + 1)).isInstanceOf(IOException.class);
    }
}
