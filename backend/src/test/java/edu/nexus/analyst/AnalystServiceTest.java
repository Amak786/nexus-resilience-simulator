package edu.nexus.analyst;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.nexus.integrity.IntegrityService;
import edu.nexus.live.LiveDataService;
import edu.nexus.model.ValidationException;
import edu.nexus.mumbai.EventService;
import edu.nexus.mumbai.MumbaiService;
import edu.nexus.mumbai.TourService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Grounding tools and the no-key / failure behaviour of the analyst (no model call is made in these tests). */
class AnalystServiceTest {

    static final ObjectMapper M = new ObjectMapper();
    static AnalystTools tools;

    @BeforeAll
    static void init() throws Exception {
        EventService events = new EventService(M);
        MumbaiService mumbai = new MumbaiService(M, events, 20000, 6000);
        IntegrityService integrity = new IntegrityService(M, mumbai, new TourService(mumbai), 6000);
        LiveDataService live = new LiveDataService(M, events, mumbai, false);
        tools = new AnalystTools(M, mumbai, integrity, live);
    }

    static AnalystService noKey(int perClient) {
        return new AnalystService(tools, M, "", "claude-opus-5-5", perClient, 1000);
    }

    @Test
    @DisplayName("AI-1 (grounding): every tool returns real computed data with a source label, or a clear error")
    void toolsReturnGroundedData() {
        ObjectNode net = tools.execute("get_network_summary", M.createObjectNode());
        assertEquals(12907, net.at("/data/graph/nodes").asInt(), "real snapshot junction count");
        assertTrue(net.get("source").asText().contains("OpenStreetMap"));
        ObjectNode integ = tools.execute("analyse_integrity", M.createObjectNode().put("area", "dadar"));
        assertTrue(integ.at("/data/totalChanges").asInt() > 0);
        assertEquals(0, integ.at("/data/statusCounts/verifiedMalicious").asInt());
        assertTrue(integ.at("/data/topChanges/0/evidenceUrl").asText().startsWith("https://www.openstreetmap.org/way/"));
        ObjectNode route = tools.execute("find_route", M.createObjectNode().put("from_lat", 19.018).put("from_lon", 72.847)
                .put("to_lat", 19.056).put("to_lon", 72.845));
        assertTrue(route.at("/data/reachable").asBoolean());
        assertTrue(route.at("/data/queuesAgree").asBoolean());
    }

    @Test
    @DisplayName("AI-2 (tool errors): unknown tools, bad areas, missing and out-of-range inputs return {error}, never an exception")
    void toolErrors() {
        assertTrue(tools.execute("delete_database", M.createObjectNode()).has("error"));
        assertTrue(tools.execute("analyse_integrity", M.createObjectNode().put("area", "london")).has("error"));
        assertTrue(tools.execute("find_route", M.createObjectNode().put("from_lat", 19.0)).has("error"));
        assertTrue(tools.execute("find_route", M.createObjectNode().put("from_lat", 40.0).put("from_lon", 72.8)
                .put("to_lat", 19.05).put("to_lon", 72.84)).has("error"), "outside Mumbai -> validation error");
        assertTrue(tools.execute("get_weather", M.createObjectNode()).has("error"), "no observation ingested -> says so");
    }

    @Test
    @DisplayName("AI-3 (no key): the analyst answers with computed evidence only and labels it EVIDENCE_ONLY")
    void noKeyFallback() {
        AnalystService a = noKey(10);
        assertFalse(a.aiConfigured());
        var r = a.ask("What changed in Dadar and how fresh is the data?", "t1");
        assertEquals("EVIDENCE_ONLY", r.answerKind());
        assertFalse(r.aiAvailable());
        assertTrue(r.notice().contains("not configured"));
        var toolsUsed = r.evidence().stream().map(AnalystService.EvidenceItem::tool).toList();
        assertTrue(toolsUsed.contains("get_data_status"));
        assertTrue(toolsUsed.contains("analyse_integrity"));
        assertTrue(toolsUsed.contains("get_recent_road_edits"));
    }

    @Test
    @DisplayName("AI-4 (limits): empty or over-long questions are rejected; per-client rate limit returns 429")
    void limits() {
        AnalystService a = noKey(2);
        assertThrows(ValidationException.class, () -> a.ask("  ", "c"));
        assertThrows(ValidationException.class, () -> a.ask("x".repeat(AnalystService.MAX_QUESTION_CHARS + 1), "c"));
        a.ask("network summary", "c");
        a.ask("network summary", "c");
        assertThrows(AnalystService.RateLimitedException.class, () -> a.ask("network summary", "c"));
        a.ask("network summary", "other-client");
    }

    @Test
    @DisplayName("AI-5 (system prompt): the grounding rules are present (no invented facts, no blocked-road claims, three sections)")
    void systemPromptRules() {
        assertTrue(AnalystService.SYSTEM.contains("Answer only from the results of the tools"));
        assertTrue(AnalystService.SYSTEM.contains("Never say a road is blocked"));
        assertTrue(AnalystService.SYSTEM.contains("Observed data, Algorithm results, Interpretation"));
        assertEquals(6, AnalystTools.SPECS.size());
    }

    @Test
    @DisplayName("SEC-1 (secrets): no API key is committed in source or configuration")
    void noSecretsInRepo() throws Exception {
        try (Stream<Path> files = Files.walk(Path.of("src"))) {
            for (Path p : files.filter(Files::isRegularFile).filter(f -> f.toString().matches(".*\\.(java|yml|yaml|properties|json)$"))
                    .filter(f -> !f.toString().contains("roads")).toList()) {
                String s = Files.readString(p);
                assertFalse(s.matches("(?s).*sk-ant-[A-Za-z0-9_-]{10,}.*"), "possible Anthropic key in " + p);
            }
        }
    }
}
