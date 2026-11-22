package edu.trace.analyst;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.trace.intel.ThreatIntelService;
import edu.trace.intel.ThreatIntelServiceTestSupport;
import edu.trace.model.ValidationException;
import edu.trace.net.AnalysisService;
import edu.trace.net.EventStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AI analyst grounding tools and its behaviour without a model key (no model call is made in these tests). */
class AnalystServiceTest {

    static final ObjectMapper M = new ObjectMapper();
    static AnalystTools tools;

    @BeforeAll
    static void init() {
        EventStore store = new EventStore(24, 1000);
        ThreatIntelService intel = ThreatIntelServiceTestSupport.loaded(M);
        String now = Instant.now().toString();
        store.ingest(new EventStore.Batch("lab-pc", "analyst-test-batch", "test", "t", now, List.of(
                new EventStore.Row(now, "TCP", "192.168.1.5", 50001, "50.16.16.211", 443, "Established", "svchost"),
                new EventStore.Row(now, "TCP", "192.168.1.5", 50002, "8.8.8.8", 443, "Established", "chrome"))));
        tools = new AnalystTools(M, new AnalysisService(store, intel), intel, store);
    }

    static AnalystService noKey(int perClient) {
        return new AnalystService(tools, M, "", "claude-opus-5-5", perClient, 1000);
    }

    @Test
    @DisplayName("AI-1 (grounding): tools return values computed by TRACE with a source label")
    void toolsGrounded() {
        var f = tools.execute("list_findings", M.createObjectNode().put("limit", 5));
        assertEquals(1, f.at("/data/total").asInt());
        assertEquals("INTEL_FEODO:50.16.16.211", f.at("/data/findings/0/id").asText());
        assertTrue(f.get("source").asText().contains("TRACE rules"));
        var inv = tools.execute("investigate_ip", M.createObjectNode().put("ip", "50.16.16.211"));
        assertEquals("abuse.ch Feodo Tracker", inv.at("/data/results/0/intel/0/list").asText());
        var net = tools.execute("network_summary", M.createObjectNode());
        assertEquals(5, net.at("/data/nodes").asInt(), "sensor, 2 processes, 2 remotes");
        var st = tools.execute("get_source_status", M.createObjectNode());
        assertEquals("LIVE", st.at("/data/mode").asText());
        var algo = tools.execute("explain_algorithm", M.createObjectNode().put("name", "bloom"));
        assertTrue(algo.at("/data/usedIn").asText().contains("Feodo"));
    }

    @Test
    @DisplayName("AI-2 (tool errors): unknown tool, unknown finding, host name instead of IP -> {error}, never an exception")
    void toolErrors() {
        assertTrue(tools.execute("block_ip", M.createObjectNode()).has("error"));
        assertTrue(tools.execute("get_finding", M.createObjectNode().put("id", "nope")).has("error"));
        assertTrue(tools.execute("investigate_ip", M.createObjectNode().put("ip", "http://evil.example/x")).has("error"));
        assertTrue(tools.execute("explain_algorithm", M.createObjectNode().put("name", "quantum")).has("error"));
    }

    @Test
    @DisplayName("AI-3 (no key / provider down): deterministic EVIDENCE_ONLY answer, labelled, using the relevant tools")
    void evidenceOnly() {
        AnalystService a = noKey(10);
        assertFalse(a.aiConfigured());
        var r = a.ask("Why does 50.16.16.211 need attention?", "c1");
        assertEquals("EVIDENCE_ONLY", r.answerKind());
        var used = r.evidence().stream().map(AnalystService.EvidenceItem::tool).toList();
        assertTrue(used.containsAll(List.of("get_source_status", "investigate_ip", "list_findings")), used.toString());
        assertTrue(r.notice().contains("not configured"));
    }

    @Test
    @DisplayName("AI-4 (limits and prompt): length limits, per-client rate limit, grounding rules present in the system prompt")
    void limitsAndPrompt() {
        AnalystService a = noKey(2);
        assertThrows(ValidationException.class, () -> a.ask("", "c"));
        assertThrows(ValidationException.class, () -> a.ask("x".repeat(AnalystService.MAX_QUESTION_CHARS + 1), "c"));
        a.ask("network", "c");
        a.ask("network", "c");
        assertThrows(AnalystService.RateLimitedException.class, () -> a.ask("network", "c"));
        assertTrue(AnalystService.SYSTEM.contains("evidence, not proof"));
        assertTrue(AnalystService.SYSTEM.contains("Never say a host is compromised"));
        assertTrue(AnalystService.SYSTEM.contains("Do not use country or location"));
    }

    @Test
    @DisplayName("SEC-1 (secrets): no API key or token value is committed in source or configuration")
    void noSecrets() throws Exception {
        try (Stream<Path> files = Files.walk(Path.of("src"))) {
            for (Path p : files.filter(Files::isRegularFile).filter(f -> f.toString().matches(".*\\.(java|yml|yaml|properties)$")).toList()) {
                String s = Files.readString(p);
                assertFalse(s.matches("(?s).*sk-ant-[A-Za-z0-9_-]{10,}.*"), "possible Anthropic key in " + p);
                assertFalse(s.matches("(?s).*TRACE_(INGEST|VIEW)_TOKEN:[^}]+}.*"), "token default value in " + p);
            }
        }
    }
}
