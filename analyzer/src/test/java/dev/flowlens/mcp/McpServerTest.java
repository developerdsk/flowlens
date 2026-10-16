package dev.flowlens.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.flowlens.Json;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class McpServerTest {

    private List<JsonNode> run(Path project, String... lines) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new McpServer(project, new PrintStream(out, true, StandardCharsets.UTF_8))
                .run(new ByteArrayInputStream(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
        List<JsonNode> res = new ArrayList<>();
        for (String l : out.toString(StandardCharsets.UTF_8).split("\n")) if (!l.isBlank()) res.add(Json.MAPPER.readTree(l));
        return res;
    }

    @Test
    void handshakeToolsAndQueries() throws Exception {
        Path sample = Path.of("../examples/sample-app").toAbsolutePath().normalize();
        List<JsonNode> res = run(sample,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"table_access\",\"arguments\":{\"table\":\"account\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"endpoint_flow\",\"arguments\":{\"endpoint\":\"POST /api/accounts\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"impact_of_change\",\"arguments\":{\"symbol\":\"AuditService.record\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"nope\"}");
        assertEquals(6, res.size(), "notification must not be answered");
        assertEquals("2025-06-18", res.get(0).at("/result/protocolVersion").asText());
        assertTrue(res.get(1).at("/result/tools").size() >= 6);
        String tables = res.get(2).at("/result/content/0/text").asText();
        assertTrue(tables.contains("UPSERT") && tables.contains("POST /api/accounts"), tables);
        String flow = res.get(3).at("/result/content/0/text").asText();
        assertTrue(flow.contains("AccountServiceImpl.create()") && flow.contains("DB UPSERT account"), flow);
        assertTrue(res.get(4).at("/result/content/0/text").asText().contains("POST /api/accounts"));
        assertEquals(-32601, res.get(5).at("/error/code").asInt());
    }
}
