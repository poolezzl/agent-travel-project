package com.agenttravel.backend.traveltools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.database.mode", havingValue = "mysql")
class ExportRenderer {
    enum Format {
        PDF("pdf", "application/pdf"), ICS("ics", "text/calendar");

        final String extension;
        final String mime;

        Format(String extension, String mime) {
            this.extension = extension;
            this.mime = mime;
        }
    }

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private final String fontPath;
    private final ZoneId zone;

    ExportRenderer(@Value("${app.s26.pdf-font:}") String fontPath,
            @Value("${app.adapters.qweather-zone-id:Asia/Shanghai}") String zone) {
        this.fontPath = fontPath;
        this.zone = ZoneId.of(zone);
    }

    byte[] render(Format format, String title, JsonNode plan, String version, String job, Instant now)
            throws IOException {
        if (!plan.path("days").isArray() || plan.path("days").isEmpty()) {
            throw new IOException("固定版本缺少日程");
        }
        return format == Format.PDF ? pdf(title, plan, job, now) : calendar(title, plan, version, job, now);
    }

    private byte[] calendar(String title, JsonNode plan, String version, String job, Instant now) {
        StringBuilder result = new StringBuilder();
        for (String line : List.of("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Agent Travel//Trip Export//ZH",
                "CALSCALE:GREGORIAN", "X-WR-CALNAME:" + escape(title), "X-TRIP-EXPORT-ID:" + job)) {
            append(result, line);
        }
        for (JsonNode day : plan.path("days")) {
            LocalDate date = LocalDate.parse(day.path("date").asText());
            int index = 0;
            for (JsonNode segment : day.path("segments")) {
                event(result, date, segment, title, version, ++index, now);
            }
            if (index == 0) {
                event(result, date, MissingNode.getInstance(), title, version, 0, now);
            }
        }
        append(result, "END:VCALENDAR");
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void event(StringBuilder result, LocalDate date, JsonNode segment, String title, String version,
            int index, Instant now) {
        append(result, "BEGIN:VEVENT");
        String event = version + "/" + date + "/" + index + "/" + segment.path("point_id").asText();
        append(result, "UID:" + UUID.nameUUIDFromBytes(event.getBytes(StandardCharsets.UTF_8)) + "@agent-travel");
        append(result, "DTSTAMP:" + UTC.format(now));
        String start = segment.path("start_time").asText("");
        String end = segment.path("end_time").asText("");
        if (start.isBlank() && end.isBlank()) {
            append(result, "DTSTART;VALUE=DATE:" + DateTimeFormatter.BASIC_ISO_DATE.format(date));
            append(result, "DTEND;VALUE=DATE:" + DateTimeFormatter.BASIC_ISO_DATE.format(date.plusDays(1)));
        } else {
            LocalTime startTime = LocalTime.parse(start);
            LocalTime endTime = LocalTime.parse(end);
            LocalDate endDate = endTime.isBefore(startTime) ? date.plusDays(1) : date;
            append(result, "DTSTART:" + UTC.format(date.atTime(startTime).atZone(zone)));
            if (!startTime.equals(endTime)) {
                append(result, "DTEND:" + UTC.format(endDate.atTime(endTime).atZone(zone)));
            }
        }
        append(result, "SUMMARY:" + escape(segment.path("name").asText(title)));
        if (segment.path("district").isTextual()) {
            append(result, "LOCATION:" + escape(segment.path("district").asText()));
        }
        if (segment.path("is_estimated").asBoolean()) {
            append(result, "DESCRIPTION:行程时长为估算。");
        }
        append(result, "END:VEVENT");
    }

    private static String escape(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Cntrl}&&[^\\n]]", "")
                .replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;");
    }

    private static void append(StringBuilder calendar, String line) {
        int bytes = 0;
        for (int i = 0; i < line.length();) {
            int codePoint = line.codePointAt(i);
            String character = Character.toString(codePoint);
            int length = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + length > 75) {
                calendar.append("\r\n ");
                bytes = 1;
            }
            calendar.append(character);
            bytes += length;
            i += Character.charCount(codePoint);
        }
        calendar.append("\r\n");
    }

    private byte[] pdf(String title, JsonNode plan, String job, Instant now) throws IOException {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream();
                var fontSource = Files.newInputStream(font())) {
            var font = PDType0Font.load(document, fontSource, true);
            List<String> lines = new ArrayList<>();
            lines.add(title);
            lines.add(plan.path("notice").asText(""));
            for (JsonNode day : plan.path("days")) {
                lines.add(day.path("date").asText() + " " + day.path("weekday").asText(""));
                JsonNode weather = day.path("weather");
                if (fresh(plan, weather, "weather", now)) {
                    String label = "forecast".equals(weather.path("data_type").asText()) ? "天气预报：" : "气候参考：";
                    lines.add(label + weather.path("condition").asText("待核实"));
                } else {
                    lines.add("天气资料已过期或缺少来源，请查阅最新预报。");
                }
                for (JsonNode segment : day.path("segments")) {
                    String name = segment.path("name").asText();
                    String start = segment.path("start_time").asText("");
                    String end = segment.path("end_time").asText("");
                    String time = start + " - " + end;
                    if (start.isBlank() || end.isBlank()) {
                        time = start.isBlank() && end.isBlank() ? "全天" : "时间待核实";
                    }
                    lines.add(time + "  " + name + (segment.path("is_estimated").asBoolean() ? "（时长估算）" : ""));
                    JsonNode route = segment.path("transport_to_next");
                    if (route.path("is_estimated").asBoolean()) {
                        lines.add("下一段交通耗时估算：" + route.path("duration_min").asText("待核实") + " 分钟。");
                    } else if (route.isObject()) {
                        lines.add(fresh(plan, route, "map", now)
                                ? "下一段交通耗时：" + route.path("duration_min").asText("待核实") + " 分钟。"
                                : "下一段交通资料已过期或缺少有效来源。");
                    }
                }
                for (JsonNode meal : day.path("meals")) {
                    String label = "dinner".equals(meal.path("meal").asText()) ? "晚餐参考" : "午餐参考";
                    candidates(lines, plan, meal.path("candidates"), label, now);
                }
                candidates(lines, plan, day.path("lodging").path("candidates"), "住宿参考", now);
                for (JsonNode booking : day.path("reservations")) {
                    if (booking.path("reservation_required").asBoolean() || booking.path("stale").asBoolean()) {
                        lines.add("预约：" + pointName(day, booking.path("point_id").asText()) + "，请核对官方最新规则。");
                    }
                }
                for (JsonNode warning : day.path("warnings")) {
                    lines.add(warning.asText());
                }
                lines.add("");
            }
            List<String> wrapped = new ArrayList<>();
            for (String line : lines) {
                wrap(font, line, wrapped);
            }
            for (int start = 0; start < wrapped.size(); start += 42) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    stream.setNonStrokingColor(0.42f);
                    text(stream, font, 10, 48, 804, "私人行程 · 仅供本人使用 · " + job);
                    stream.setNonStrokingColor(0f);
                    for (int i = start; i < Math.min(start + 42, wrapped.size()); i++) {
                        text(stream, font, 12, 48, 777 - (i - start) * 17, wrapped.get(i));
                    }
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    static String pointName(JsonNode day, String point) {
        for (JsonNode segment : day.path("segments")) {
            if (point.equals(segment.path("point_id").asText())) {
                return segment.path("name").asText(point);
            }
        }
        return point;
    }

    static Instant instant(String value) {
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (java.time.DateTimeException exception) {
            return null;
        }
    }

    private Path font() throws IOException {
        if (!fontPath.isBlank()) {
            return Path.of(fontPath);
        }
        String windows = System.getenv("WINDIR");
        if (windows != null) {
            Path fallback = Path.of(windows, "Fonts", "simhei.ttf");
            if (Files.isRegularFile(fallback)) {
                return fallback;
            }
        }
        throw new IOException("缺少可嵌入的中文字体");
    }

    private static void candidates(List<String> lines, JsonNode plan, JsonNode candidates, String label, Instant now) {
        for (JsonNode candidate : candidates) {
            if (fresh(plan, candidate, "poi", now) && candidate.path("name").isTextual()) {
                lines.add(label + "：" + candidate.path("name").asText());
            }
        }
    }

    private static boolean fresh(JsonNode plan, JsonNode fact, String type, Instant now) {
        String id = fact.path("source_record_id").asText();
        if (id.isBlank() || fact.path("stale").asBoolean() || fact.path("is_estimated").asBoolean()) {
            return false;
        }
        Instant factExpiry = instant(fact.path("valid_until").asText());
        if (factExpiry != null && !factExpiry.isAfter(now)) {
            return false;
        }
        for (JsonNode source : plan.path("sources")) {
            Instant valid = instant(source.path("valid_until").asText());
            Instant retrieved = instant(source.path("retrieved_at").asText());
            if (id.equals(source.path("source_record_id").asText()) && type.equals(source.path("source_type").asText())
                    && "fresh".equals(source.path("freshness_status").asText()) && !source.path("is_estimated").asBoolean()
                    && valid != null && valid.isAfter(now) && retrieved != null && !retrieved.isAfter(now)) {
                return true;
            }
        }
        return false;
    }

    private static void wrap(PDType0Font font, String value, List<String> result) throws IOException {
        String cleaned = value.replaceAll("[\\p{Cc}\\p{Cf}]", " ");
        StringBuilder line = new StringBuilder();
        float width = 0;
        for (int i = 0; i < cleaned.length();) {
            int codePoint = cleaned.codePointAt(i);
            String character = Character.toString(codePoint);
            float advance;
            try {
                advance = font.getStringWidth(character) * 0.012f;
            } catch (IllegalArgumentException exception) {
                character = "?";
                advance = font.getStringWidth(character) * 0.012f;
            }
            if (width + advance > 495) {
                result.add(line.toString());
                line.setLength(0);
                width = 0;
            }
            line.append(character);
            width += advance;
            i += Character.charCount(codePoint);
        }
        result.add(line.toString());
    }

    private static void text(PDPageContentStream stream, PDType0Font font, int size, int x, int y, String line)
            throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(x, y);
        stream.showText(line);
        stream.endText();
    }
}
