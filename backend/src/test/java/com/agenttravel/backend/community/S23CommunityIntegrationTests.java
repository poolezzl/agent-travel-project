package com.agenttravel.backend.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

import com.agenttravel.backend.auth.JwtTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@EnabledIfEnvironmentVariable(named = "S23_IT", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.security.jwt-secret=test-only-jwt-signing-key-32-bytes",
        "server.address=127.0.0.1",
        "app.s11.enabled=false",
        "app.community.reaction-limit=3"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class S23CommunityIntegrationTests {
    private static final String ROOT = "/api/v1/community";

    @TempDir static Path images;

    @DynamicPropertySource
    static void objectDirectory(DynamicPropertyRegistry properties) {
        properties.add("app.community.object-directory", () -> images.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtTokenService tokens;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int backendPort;

    @Test
    void publicationRequiresOwnershipSafeTextAndRealImages() throws Exception {
        Member owner = member("USER");
        Member outsider = member("USER");
        UUID version = version(owner);
        publish(outsider, version, "周末游", "k1", null).andExpect(status().isNotFound());
        publish(owner, version, "<b>周末游</b>", "k2", null).andExpect(status().isBadRequest());
        publish(owner, version, "刷　赞交易", "k3", null).andExpect(status().isBadRequest());
        publish(owner, version, "\uFB03".repeat(80), "expanded-title", null)
                .andExpect(status().isBadRequest());
        publish(owner, version, "周末游", "k4",
                new MockMultipartFile("images", "bad.png", "image/png", new byte[]{1, 2, 3}))
                .andExpect(status().isBadRequest());
        publish(owner, version, "周末游", "k5",
                new MockMultipartFile("images", "broken.png", "image/png",
                        new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
                .andExpect(status().isBadRequest());
        var large = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2049, 1, BufferedImage.TYPE_INT_RGB), "png", large);
        publish(owner, version, "周末游", "oversize",
                new MockMultipartFile("images", "large.png", "image/png", large.toByteArray()))
                .andExpect(status().isBadRequest());
        String id = id(publish(owner, version, "周末游", "k6", null));
        assertThat(id(publish(owner, version, "周末游", "k6", null))).isEqualTo(id);
        publish(owner, version, "另一标题", "k6", null).andExpect(status().isConflict());
        mvc.perform(get(ROOT + "/posts/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.itinerary.days[0].points[0].name").value("故宫"));
        write(post(ROOT + "/posts/" + id + "/comments"), outsider, "comment",
                Map.of("body", "<b>内容</b>")).andExpect(status().isBadRequest());
        write(post(ROOT + "/posts/" + id + "/comments"), outsider, "expanded-comment",
                Map.of("body", "\uFB03".repeat(400))).andExpect(status().isBadRequest());
        write(post(ROOT + "/posts/" + id + "/reports"), outsider, "expanded-report",
                Map.of("reason", "OTHER", "detail", "\uFB03".repeat(400))).andExpect(status().isBadRequest());
        write(delete(ROOT + "/posts/" + id).header("If-Match", 1), outsider, "withdraw", null)
                .andExpect(status().isNotFound());
        write(post(ROOT + "/moderation/" + id).header("If-Match", 1), outsider, "pin",
                Map.of("action", "PIN")).andExpect(status().isForbidden());
        for (int i = 0; i < 4; i++) {
            publish(owner, version, "周末游补记", "more-" + i, null).andExpect(status().isOk());
        }
        publish(owner, version, "周末游补记", "over-limit", null).andExpect(status().isTooManyRequests());
    }

    @Test
    void repeatedAndConcurrentReactionsHaveExactCountsAndRateLimit() throws Exception {
        Member owner = member("USER");
        Member visitor = member("USER");
        UUID version = version(owner);
        String id = id(publish(owner, version, "故宫", "p1", null));
        String like = ROOT + "/posts/" + id + "/reactions/LIKE";
        write(put(like), owner, "self", null).andExpect(status().isForbidden());
        List<Member> people = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            people.add(member("USER"));
        }
        List<Callable<String>> calls = new ArrayList<>();
        for (Member person : people) {
            calls.add(() -> id(write(put(like), person, "one", null)));
            calls.add(() -> id(write(put(like), person, "two", null)));
        }
        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = executor.invokeAll(calls);
            for (int i = 0; i < results.size(); i += 2) {
                assertThat(results.get(i).get()).isEqualTo(results.get(i + 1).get());
            }
        }
        mvc.perform(get(ROOT + "/posts/" + id)).andExpect(jsonPath("$.likes").value(8));
        var favorite = ROOT + "/posts/" + id + "/reactions/FAVORITE";
        mvc.perform(put(like)).andExpect(status().isBadRequest());
        mvc.perform(put(like).header("Idempotency-Key", "anonymous")).andExpect(status().isUnauthorized());
        id(write(put(like), visitor, "r1", null));
        id(write(delete(like), visitor, "r2", null));
        id(write(put(favorite), visitor, "r3", null));
        write(put(like), visitor, "r4", null).andExpect(status().isTooManyRequests());
        mvc.perform(get(ROOT + "/posts/" + id)).andExpect(jsonPath("$.likes").value(8))
                .andExpect(jsonPath("$.favorites").value(1));
    }

    @Test
    void reportsRemoveContentFromEveryReadAndPriorImageLink() throws Exception {
        Member owner = member("USER");
        Member visitor = member("USER");
        Member other = member("USER");
        Member admin = member("ADMIN");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        String title = "下架-" + UUID.randomUUID();
        String id = id(publish(owner, version(owner), title, "publish",
                new MockMultipartFile("images", "../source.png", "image/png", bytes.toByteArray())));
        String path = ROOT + "/posts/" + id;
        String image = response(mvc.perform(get(path)).andExpect(header().string("Cache-Control", "no-store")))
                .path("images").get(0).asText();
        mvc.perform(get(java.net.URI.create(image))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(java.net.URI.create(image.replaceFirst("expires=[0-9]+", "expires=1"))))
                .andExpect(status().isNotFound());
        mvc.perform(get(java.net.URI.create(image + "a"))).andExpect(status().isNotFound());
        write(post(ROOT + "/moderation/" + id).header("If-Match", 1), admin, "pin",
                Map.of("action", "PIN")).andExpect(status().isOk());
        mvc.perform(get(ROOT + "/rankings").param("city", "beijing").param("theme", "FOOD").param("days", "2"))
                .andExpect(jsonPath("$[?(@.id == '%s')]", id).isNotEmpty());
        String c1 = id(write(post(path + "/comments"), visitor, "c1", Map.of("body", "真实评价", "rating", 5)));
        assertThat(id(write(post(path + "/comments"), visitor, "c1", Map.of("body", "真实评价", "rating", 5))))
                .isEqualTo(c1);
        String commentReport = path + "/comments/" + c1 + "/reports";
        id(write(post(commentReport), other, "comment-report", Map.of("reason", "SPAM")));
        String commentCase = caseId(admin, id, "COMMENT");
        write(post(ROOT + "/moderation/" + id + "/cases/" + commentCase).header("If-Match", 2), admin,
                "remove-comment", Map.of("action", "REMOVE")).andExpect(status().isOk());
        mvc.perform(get(path + "/comments")).andExpect(jsonPath("$").isEmpty());
        String report = id(write(post(path + "/reports"), visitor, "report1", Map.of("reason", "PRIVACY")));
        assertThat(id(write(post(path + "/reports"), visitor, "report2", Map.of("reason", "PRIVACY"))))
                .isEqualTo(report);
        id(write(post(path + "/reports"), other, "report", Map.of("reason", "PRIVACY")));
        String openCase = caseId(admin, id, "POST");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM report WHERE moderation_case_id=UUID_TO_BIN(?)",
                Integer.class, openCase)).isEqualTo(2);
        write(post(ROOT + "/moderation/" + id + "/cases/" + openCase).header("If-Match", 2), admin,
                "stale", Map.of("action", "REMOVE")).andExpect(status().isConflict());
        write(post(ROOT + "/moderation/" + id + "/cases/" + openCase).header("If-Match", 3), admin,
                "remove", Map.of("action", "REMOVE")).andExpect(status().isOk());
        mvc.perform(get(path)).andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(path + "/comments")).andExpect(status().isNotFound());
        mvc.perform(get(java.net.URI.create(image))).andExpect(status().isNotFound());
        mvc.perform(get(ROOT + "/posts").param("q", title)).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get(ROOT + "/rankings")).andExpect(jsonPath("$[?(@.id == '%s')]", id).isEmpty());
        write(post(path + "/reuse"), visitor, "reuse", Map.of("start_date", "2030-10-01"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rankingSelectsRatedPostsAndReviewClearsThePendingQueue() throws Exception {
        Member owner = member("USER");
        Member visitor = member("USER");
        Member admin = member("ADMIN");
        String id = id(publish(owner, version(owner), "榜单评分", "publish", null));
        String path = ROOT + "/posts/" + id;
        mvc.perform(get(ROOT + "/rankings")).andExpect(jsonPath("$[?(@.id == '%s')]", id).isEmpty());
        mvc.perform(get(ROOT + "/moderation").header("Authorization", admin.authorization()))
                .andExpect(jsonPath("$[?(@.post_id == '%s')]", id).isNotEmpty());
        write(post(ROOT + "/moderation/" + id).header("If-Match", 1), admin, "approve",
                Map.of("action", "APPROVE")).andExpect(status().isOk());
        mvc.perform(get(ROOT + "/moderation").header("Authorization", admin.authorization()))
                .andExpect(jsonPath("$[?(@.post_id == '%s')]", id).isEmpty());
        id(write(post(path + "/comments"), visitor, "rating", Map.of("body", "行程清楚", "rating", 5)));
        JsonNode ranked = response(mvc.perform(get(ROOT + "/rankings").param("city", "beijing")
                .param("theme", "FOOD").param("days", "2").param("limit", "100")));
        JsonNode result = null;
        for (JsonNode row : ranked) {
            if (row.path("id").asText().equals(id)) {
                result = row;
            }
        }
        assertThat(result).isNotNull();
        assertThat(result.path("score").asDouble()).isGreaterThan(0.35);
        assertThat(result.path("estimated_dimensions").get(0).asText()).isEqualTo("CROWD");
        assertThat(result.path("unavailable_dimensions").get(0).asText()).isEqualTo("SEASON");
        mvc.perform(get(ROOT + "/rankings").param("duration", "WEEKEND"))
                .andExpect(jsonPath("$[?(@.id == '%s')]", id).isNotEmpty());
        mvc.perform(get(ROOT + "/rankings").param("duration", "SHORT"))
                .andExpect(jsonPath("$[?(@.id == '%s')]", id).isEmpty());
        jdbc.update("UPDATE post SET published_at=UTC_TIMESTAMP(6)-INTERVAL 90 DAY WHERE id=UUID_TO_BIN(?)", id);
        mvc.perform(get(ROOT + "/rankings")).andExpect(jsonPath("$[?(@.id == '%s')]", id).isEmpty());
        mvc.perform(get(ROOT + "/posts").param("q", "榜单评分")).andExpect(jsonPath("$[?(@.id == '%s')]", id).isNotEmpty());
        mvc.perform(get(ROOT + "/posts").param("limit", "101")).andExpect(status().isBadRequest());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "S23_NGINX_EXE", matches = ".+")
    void cachingProxyNeverRetainsWithdrawnCommunityContent(
            @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path proxyDirectory) throws Exception {
        Member owner = member("USER");
        Member admin = member("ADMIN");
        String title = "proxy-" + UUID.randomUUID();
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        String id = id(publish(owner, version(owner), title, "publish-proxy",
                new MockMultipartFile("images", "proof.png", "image/png", bytes.toByteArray())));
        String postPath = ROOT + "/posts/" + id;
        String viewPath = ROOT + ";view=plain/posts/" + id;
        id(write(post(ROOT + "/moderation/" + id).header("If-Match", 1), admin, "pin-proxy",
                Map.of("action", "PIN")));

        int proxyPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            proxyPort = socket.getLocalPort();
        }
        Files.createDirectory(proxyDirectory.resolve("logs"));
        Files.createDirectory(proxyDirectory.resolve("temp"));
        Files.writeString(proxyDirectory.resolve("nginx.conf"), """
                worker_processes 1;
                pid nginx.pid;
                error_log logs/error.log warn;
                events { worker_connections 64; }
                http {
                    access_log off;
                    proxy_cache_path cache keys_zone=s23_test:1m inactive=2m;
                    server {
                        listen 127.0.0.1:%d;
                        proxy_cache s23_test;
                        proxy_cache_valid 200 1m;
                        add_header X-S23-Cache $upstream_cache_status always;
                        location = /proxy-control {
                            proxy_pass http://127.0.0.1:%d/actuator/info;
                            proxy_ignore_headers Cache-Control Expires;
                        }
                        location / {
                            proxy_pass http://127.0.0.1:%d;
                        }
                    }
                }
                """.formatted(proxyPort, backendPort, backendPort));
        List<String> nginx = List.of(System.getenv("S23_NGINX_EXE"), "-p",
                proxyDirectory.toAbsolutePath().toString().replace('\\', '/') + "/", "-c", "nginx.conf");
        var command = new ArrayList<>(nginx);
        command.addAll(List.of("-g", "daemon off;"));
        Process proxy = new ProcessBuilder(command).directory(proxyDirectory.toFile()).redirectErrorStream(true)
                .redirectOutput(proxyDirectory.resolve("process.log").toFile()).start();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            HttpResponse<byte[]> detail = awaitProxy(client, proxyPort, postPath, proxy);
            assertThat(detail.statusCode()).isEqualTo(200);
            var control = proxyGet(client, proxyPort, "/proxy-control");
            assertThat(control.statusCode()).isEqualTo(200);
            assertThat(control.headers().firstValue("X-S23-Cache")).contains("MISS");
            assertThat(proxyGet(client, proxyPort, "/proxy-control").headers()
                    .firstValue("X-S23-Cache")).contains("HIT");
            String image = mapper.readTree(detail.body()).path("images").get(0).asText();
            String feed = ROOT + "/posts?q=" + title;
            String rankings = ROOT + "/rankings?city=beijing&duration=WEEKEND&limit=100";
            for (String path : List.of(postPath, viewPath, postPath + "/comments", image, feed, rankings)) {
                for (int repeat = 0; repeat < 2; repeat++) {
                    var response = proxyGet(client, proxyPort, path);
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
                    assertThat(response.headers().firstValue("X-S23-Cache")).contains("MISS");
                    if (path.equals(feed) || path.equals(rankings)) {
                        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains(id);
                    }
                }
            }

            id(write(post(ROOT + "/moderation/" + id).header("If-Match", 2), admin, "withdraw-proxy",
                    Map.of("action", "WITHDRAW")));
            for (String path : List.of(postPath, viewPath, postPath + "/comments", image)) {
                var response = proxyGet(client, proxyPort, path);
                assertThat(response.statusCode()).isEqualTo(404);
                assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
                assertThat(response.headers().firstValue("X-S23-Cache")).contains("MISS");
            }
            for (String path : List.of(feed, rankings)) {
                var response = proxyGet(client, proxyPort, path);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("X-S23-Cache")).contains("MISS");
                assertThat(new String(response.body(), StandardCharsets.UTF_8)).doesNotContain(id);
            }
        } finally {
            if (proxy.isAlive()) {
                var stop = new ArrayList<>(nginx);
                stop.addAll(List.of("-s", "stop"));
                new ProcessBuilder(stop).directory(proxyDirectory.toFile()).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(5, TimeUnit.SECONDS);
                if (!proxy.waitFor(5, TimeUnit.SECONDS)) {
                    proxy.destroyForcibly();
                }
            }
        }
    }

    private HttpResponse<byte[]> awaitProxy(HttpClient client, int proxyPort, String path, Process proxy)
            throws Exception {
        for (int attempt = 0; attempt < 30 && proxy.isAlive(); attempt++) {
            try {
                return proxyGet(client, proxyPort, path);
            } catch (IOException exception) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("测试代理无法启动，请检查临时目录内的代理日志");
    }

    private HttpResponse<byte[]> proxyGet(HttpClient client, int proxyPort, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + proxyPort + path))
                .timeout(Duration.ofSeconds(3)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void reuseCreatesOnePrivateDraftWithOnlyAllowedFields() throws Exception {
        Member owner = member("USER");
        Member reader = member("USER");
        String postId = id(publish(owner, version(owner), "两天行程", "p1", null));
        String reuse = ROOT + "/posts/" + postId + "/reuse";
        write(post(reuse), owner, "self-copy", Map.of("start_date", "2030-10-01"))
                .andExpect(status().isForbidden());
        String draft = id(write(post(reuse), reader, "copy1", Map.of("start_date", "2030-10-01")));
        assertThat(id(write(post(reuse), reader, "copy2", Map.of("start_date", "2030-10-01")))).isEqualTo(draft);
        String draftPath = ROOT + "/drafts/" + draft;
        String body = mvc.perform(get(draftPath).header("Authorization", reader.authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.start_date").value("2030-10-01"))
                .andExpect(jsonPath("$.end_date").value("2030-10-02"))
                .andExpect(jsonPath("$.itinerary.days[0].points[0].name").value("故宫"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("私密偏好", "酒店地址", "酒店坐标", "个人备注", "2026-09", "hotel-secret");
        mvc.perform(get(draftPath).header("Authorization", owner.authorization())).andExpect(status().isNotFound());
        mvc.perform(get(draftPath)).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT + "/posts/" + postId)).andExpect(jsonPath("$.reuses").value(1));
    }

    private Member member(String role) {
        UUID user = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO user_account(id,username,password_hash,role)
                VALUES(UUID_TO_BIN(?),?,'synthetic',?)
                """, user.toString(), "s23-test-" + user, role);
        jdbc.update("""
                INSERT INTO user_session(id,user_id,refresh_token_hash,issued_at,expires_at)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UNHEX(SHA2(?,256)),UTC_TIMESTAMP(6),
                      UTC_TIMESTAMP(6) + INTERVAL 1 HOUR)
                """, session.toString(), user.toString(), session.toString());
        return new Member(user, "Bearer " + tokens.createAccessToken(user, role, session, Instant.now().getEpochSecond()));
    }

    private UUID version(Member owner) {
        UUID trip = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO trip(id,owner_id,title,start_date,end_date,status)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'本人行程','2026-09-01','2026-09-02','ACTIVE')
                """, trip.toString(), owner.id().toString());
        jdbc.update("""
                INSERT INTO trip_version(id,trip_id,version_no,etag,content_hash,version_status,schema_version,created_by)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),1,UNHEX(SHA2(?,256)),UNHEX(SHA2(?,256)),
                      'PUBLISHED','s19-plan-v1',UUID_TO_BIN(?))
                """, version.toString(), trip.toString(), version.toString(), version.toString(), owner.id().toString());
        jdbc.update("UPDATE trip SET current_version_id=UUID_TO_BIN(?) WHERE id=UUID_TO_BIN(?)",
                version.toString(), trip.toString());
        jdbc.update("INSERT INTO plan_snapshot(id,trip_version_id,plan_json) VALUES(UUID_TO_BIN(UUID()),UUID_TO_BIN(?),?)",
                version.toString(), """
                {"sample_only":false,"trip":{"city_id":"beijing","city_name":"北京","preferences":["私密偏好"]},
                 "days":[{"date":"2026-09-01","city_id":"beijing",
                           "lodging":{"address":"酒店地址","location":"酒店坐标"},
                           "segments":[{"point_id":"poi-1","name":"故宫","category":"attraction","notes":"个人备注"},
                                       {"point_id":"hotel-secret","name":"住宿点","category":"hotel"}]},
                          {"date":"2026-09-02","city_id":"beijing","segments":[]}],
                 "quality":{"status":"pass"}}
                """);
        return version;
    }

    private ResultActions publish(Member actor, UUID version, String title, String key, MockMultipartFile image)
            throws Exception {
        var builder = multipart(ROOT + "/posts").file(new MockMultipartFile("post", "", "application/json",
                mapper.writeValueAsBytes(Map.of("source_version_id", version, "title", title, "body", "游记正文",
                        "theme", "FOOD", "image_licenses", image == null ? List.of() : List.of(Map.of("kind", "OWN"))))));
        if (image != null) {
            builder.file(image);
        }
        return write(builder, actor, key, null);
    }

    private ResultActions write(MockHttpServletRequestBuilder request, Member member, String key, Object body)
            throws Exception {
        request.header("Authorization", member.authorization()).header("Idempotency-Key", key);
        if (body != null) {
            request.contentType("application/json").content(mapper.writeValueAsBytes(body));
        }
        return mvc.perform(request);
    }

    private String caseId(Member admin, String postId, String targetType) throws Exception {
        for (JsonNode item : response(mvc.perform(get(ROOT + "/moderation")
                .header("Authorization", admin.authorization())))) {
            if (item.path("post_id").asText().equals(postId) && item.path("target_type").asText().equals(targetType)) {
                return item.path("case_id").asText();
            }
        }
        throw new AssertionError("治理案件未进入待办");
    }

    private String id(ResultActions response) throws Exception {
        return response(response).path("id").asText();
    }

    private JsonNode response(ResultActions response) throws Exception {
        return mapper.readTree(response.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private record Member(UUID id, String authorization) {}
}
