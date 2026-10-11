package dev.flowlens.server;

import com.fasterxml.jackson.databind.JsonNode;
import dev.flowlens.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** B7, B8, B9, D1: the local engine's API and its security checks. */
class ServerTest {

    @TempDir Path dir;
    Server server;

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    private int start(String token) throws IOException {
        Server.Config c = new Server.Config();
        c.port = 0;
        c.token = token;
        c.allowPicker = false;
        server = new Server(c);
        return server.start();
    }

    /** Raw HTTP so we control Host/Origin headers exactly. */
    private static Response raw(int port, String method, String path, String host, String origin, String contentType, String token, String body) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder req = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n");
            if (origin != null) req.append("Origin: ").append(origin).append("\r\n");
            if (contentType != null) req.append("Content-Type: ").append(contentType).append("\r\n");
            if (token != null) req.append(Server.TOKEN_HEADER).append(": ").append(token).append("\r\n");
            req.append("Content-Length: ").append(b.length).append("\r\n\r\n");
            OutputStream os = s.getOutputStream();
            os.write(req.toString().getBytes(StandardCharsets.UTF_8));
            os.write(b);
            os.flush();
            InputStream in = s.getInputStream();
            String all = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int code = Integer.parseInt(all.substring(9, 12));
            String respBody = all.substring(all.indexOf("\r\n\r\n") + 4);
            return new Response(code, respBody);
        }
    }

    record Response(int code, String body) {
        JsonNode json() throws IOException { return Json.MAPPER.readTree(body); }
    }

    private Path project() throws IOException {
        Path p = dir.resolve("proj/src/main/java/demo");
        Files.createDirectories(p);
        Files.writeString(p.resolve("C.java"), "package demo; @org.springframework.web.bind.annotation.RestController class C { @org.springframework.web.bind.annotation.GetMapping(\"/hi\") void hi() {} }");
        return dir.resolve("proj");
    }

    private static String pathJson(Path p) {
        return "{\"path\":" + Json.MAPPER.valueToTree(p.toString()) + "}";
    }

    @Test
    void analyzesAProjectFolder() throws Exception {
        int port = start(null);
        Response r = raw(port, "POST", "/api/analyze", "127.0.0.1:" + port, null, "application/json", null, pathJson(project()));
        assertEquals(200, r.code(), r.body());
        assertEquals("GET /hi", r.json().get("nodes").get(0).get("http").asText());
    }

    @Test
    void foreignHostIsRejected_DnsRebinding_B8() throws Exception {
        int port = start(null);
        Response r = raw(port, "POST", "/api/analyze", "evil.example.com:" + port, null, "application/json", null, pathJson(project()));
        assertEquals(403, r.code());
        assertEquals(403, raw(port, "GET", "/", "evil.example.com", null, null, null, null).code());
    }

    @Test
    void crossOriginAndSimpleRequestsAreRejected_B8() throws Exception {
        int port = start(null);
        String host = "127.0.0.1:" + port;
        assertEquals(403, raw(port, "POST", "/api/analyze", host, "https://evil.example.com", "application/json", null, pathJson(project())).code());
        assertEquals(415, raw(port, "POST", "/api/analyze", host, null, "text/plain", null, pathJson(project())).code());
        assertEquals(200, raw(port, "POST", "/api/analyze", host, "http://" + host, "application/json", null, pathJson(project())).code());
    }

    @Test
    void tokenIsRequiredInDesktopMode_D1() throws Exception {
        int port = start("s3cret");
        String host = "127.0.0.1:" + port;
        assertEquals(401, raw(port, "GET", "/api/info", host, null, null, null, null).code());
        assertEquals(401, raw(port, "GET", "/api/info", host, null, null, "wrong", null).code());
        Response ok = raw(port, "GET", "/api/info", host, null, null, "s3cret", null);
        assertEquals(200, ok.code());
        assertTrue(ok.json().get("desktop").asBoolean());
    }

    @Test
    void errorsAreValidJson_B7() throws Exception {
        int port = start(null);
        Response r = raw(port, "POST", "/api/analyze", "127.0.0.1:" + port, null, "application/json", null, "{bad");
        assertEquals(400, r.code());
        assertTrue(r.json().get("error").asText().startsWith("Invalid JSON"));
        Response missing = raw(port, "POST", "/api/analyze", "127.0.0.1:" + port, null, "application/json", null, pathJson(dir.resolve("definitely-not-here")));
        assertEquals(400, missing.code());
        assertTrue(missing.json().get("error").asText().contains("does not exist"));
        Response relative = raw(port, "POST", "/api/analyze", "127.0.0.1:" + port, null, "application/json", null, "{\"path\":\"relative/dir\"}");
        assertEquals(400, relative.code());
    }

    @Test
    void jobsReportProgressAndResult_D11() throws Exception {
        int port = start(null);
        String host = "127.0.0.1:" + port;
        Response started = raw(port, "POST", "/api/jobs", host, null, "application/json", null, pathJson(project()));
        assertEquals(202, started.code());
        String id = started.json().get("id").asText();
        JsonNode status = null;
        for (int i = 0; i < 100; i++) {
            status = raw(port, "GET", "/api/jobs/" + id, host, null, null, null, null).json();
            if (!"running".equals(status.get("state").asText())) break;
            Thread.sleep(50);
        }
        assertEquals("done", status.get("state").asText());
        assertEquals(1, status.get("graph").get("nodes").size());
    }

    @Test
    void busyPortFallsBackToTheNextOne_B9() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
            Server.Config c = new Server.Config();
            c.port = taken.getLocalPort();
            server = new Server(c);
            int port = server.start();
            assertNotEquals(taken.getLocalPort(), port);
            HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/info").openConnection();
            assertEquals(200, conn.getResponseCode());
        }
    }

    @Test
    void staticViewerIsServed() throws Exception {
        int port = start(null);
        Response r = raw(port, "GET", "/", "localhost:" + port, null, null, null, null);
        assertEquals(200, r.code());
        assertTrue(r.body().contains("FlowLens"));
        assertEquals(400, raw(port, "GET", "/../pom.xml", "localhost:" + port, null, null, null, null).code());
    }
}
