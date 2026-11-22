package edu.trace.analyst;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.trace.dto.LabDtos.AlgorithmInfo;
import edu.trace.intel.ThreatIntelService;
import edu.trace.model.ValidationException;
import edu.trace.net.AnalysisService;
import edu.trace.net.EventStore;
import edu.trace.service.AlgorithmCatalog;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only way the AI analyst learns anything: each tool runs TRACE's own logic and returns a small labelled JSON result.
 * The model never sees the raw event store and cannot obtain a value the tools did not return.
 */
@Component
public class AnalystTools {

    public record ToolSpec(String name, String description, Map<String, Object> properties, List<String> required) {}

    public static final List<ToolSpec> SPECS = List.of(
            new ToolSpec("get_source_status", "Sensors (LIVE / DELAYED / DISCONNECTED, last batch, last observation) and threat-feed freshness "
                    + "(last successful download, errors). Use before any claim about how current data is.", Map.of(), List.of()),
            new ToolSpec("list_findings", "Findings produced by TRACE's documented rules, highest score first, with severity, confidence, "
                    + "category (INTEL_MATCH or HEURISTIC), status and evidence.",
                    Map.of("limit", Map.of("type", "integer", "description", "1-10")), List.of()),
            new ToolSpec("get_finding", "One finding by id with full evidence, what is known, what is uncertain and suggested next steps.",
                    Map.of("id", Map.of("type", "string")), List.of("id")),
            new ToolSpec("investigate_ip", "Investigate an IPv4/IPv6 address or a domain name (domains are resolved with DNS and every address is "
                    + "checked): local observations, threat-list matches, announcing network (RIPEstat) and AbuseIPDB if configured.",
                    Map.of("ip", Map.of("type", "string", "description", "IP address or domain name")), List.of("ip")),
            new ToolSpec("network_summary", "Graph statistics computed now: nodes, edges, DSU components, articulation points, bridges, most central "
                    + "nodes (Brandes betweenness) and the change in remote addresses over the last 15 minutes.", Map.of(), List.of()),
            new ToolSpec("explain_algorithm", "Purpose, complexity and limitations of one TRACE algorithm from the catalogue.",
                    Map.of("name", Map.of("type", "string", "description", "e.g. dijkstra, bloom, dsu, max flow, quickselect, red-black, segment, centrality, bfs, a*")),
                    List.of("name")));

    private final ObjectMapper mapper;
    private final AnalysisService analysis;
    private final ThreatIntelService intel;
    private final EventStore store;

    public AnalystTools(ObjectMapper mapper, AnalysisService analysis, ThreatIntelService intel, EventStore store) {
        this.mapper = mapper;
        this.analysis = analysis;
        this.intel = intel;
        this.store = store;
    }

    public ObjectNode execute(String name, JsonNode input) {
        try {
            return switch (name) {
                case "get_source_status" -> sourceStatus();
                case "list_findings" -> {
                    int limit = input == null ? 5 : Math.max(1, Math.min(10, input.path("limit").asInt(5)));
                    List<AnalysisService.Finding> fs = analysis.findings();
                    ObjectNode n = mapper.createObjectNode();
                    n.put("total", fs.size());
                    n.set("findings", mapper.valueToTree(fs.subList(0, Math.min(limit, fs.size())).stream().map(this::brief).toList()));
                    n.set("scoringRule", mapper.valueToTree(AnalysisService.SCORING_RULE));
                    yield wrap("findings", "TRACE rules over sensor observations and threat lists", n);
                }
                case "get_finding" -> {
                    String id = input == null ? "" : input.path("id").asText("");
                    yield analysis.findings().stream().filter(f -> f.id().equals(id)).findFirst()
                            .map(f -> wrap("finding " + id, "TRACE rules", mapper.valueToTree(f)))
                            .orElse(error("no finding with id " + id));
                }
                case "investigate_ip" -> wrap("investigation", "sensor observations + abuse.ch / Spamhaus lists + RIPEstat (+ AbuseIPDB if configured)",
                        mapper.valueToTree(analysis.lookup(input == null ? null : input.path("ip").asText(null), true)));
                case "network_summary" -> {
                    AnalysisService.GraphView g = analysis.graph(60);
                    ObjectNode n = mapper.createObjectNode();
                    n.put("nodes", g.nodes());
                    n.put("edges", g.edges());
                    n.put("components", g.components());
                    n.put("largestComponent", g.largestComponent());
                    n.put("articulationPoints", g.articulationPoints());
                    n.put("bridges", g.bridges());
                    n.set("mostCentral", mapper.valueToTree(g.mostCentral()));
                    n.set("algorithmRuns", mapper.valueToTree(g.runs()));
                    n.set("changesLast15Min", mapper.valueToTree(analysis.changes(15)));
                    n.put("note", g.note());
                    yield wrap("network graph summary", "graph built from sensor observations", n);
                }
                case "explain_algorithm" -> {
                    String q = input == null ? "" : input.path("name").asText("").toLowerCase();
                    AlgorithmInfo hit = AlgorithmCatalog.ALL.stream().filter(a -> (a.name() + " " + a.id()).toLowerCase().contains(q.replace("*", "")))
                            .findFirst().orElse(null);
                    yield hit == null ? error("no algorithm matching '" + q + "'") : wrap("algorithm " + hit.name(), "TRACE algorithm catalogue", mapper.valueToTree(hit));
                }
                default -> error("unknown tool " + name);
            };
        } catch (ValidationException e) {
            return error(e.getMessage());
        } catch (RuntimeException e) {
            return error("tool failed: " + e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> brief(AnalysisService.Finding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.id());
        m.put("title", f.title());
        m.put("category", f.category());
        m.put("severity", f.severity());
        m.put("confidence", f.confidence());
        m.put("score", f.score());
        m.put("status", f.status());
        m.put("lastSeen", f.lastSeen());
        m.put("observations", f.observations());
        m.put("why", f.why());
        return m;
    }

    private ObjectNode sourceStatus() {
        ObjectNode n = mapper.createObjectNode();
        Instant now = store.now();
        List<Map<String, Object>> sensors = new ArrayList<>();
        for (EventStore.SensorInfo s : store.sensors()) {
            long idle = Duration.between(s.lastBatchAt(), now).toSeconds();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sensorId", s.sensorId());
            m.put("status", idle <= 90 ? "LIVE" : idle <= 900 ? "DELAYED" : "DISCONNECTED");
            m.put("lastBatchAt", s.lastBatchAt().toString());
            m.put("lastObservedAt", s.lastObservedAt() == null ? null : s.lastObservedAt().toString());
            m.put("rows", s.rows());
            sensors.add(m);
        }
        n.put("serverTime", now.toString());
        n.set("sensors", mapper.valueToTree(sensors));
        n.put("mode", sensors.isEmpty() ? "NO_SENSOR" : sensors.stream().anyMatch(s -> "LIVE".equals(s.get("status"))) ? "LIVE" : "HISTORICAL");
        n.set("threatFeeds", mapper.valueToTree(intel.status()));
        return wrap("source status", "TRACE ingestion log and feed scheduler", n);
    }

    private ObjectNode wrap(String what, String source, JsonNode data) {
        ObjectNode o = mapper.createObjectNode();
        o.put("result", what);
        o.put("source", source);
        o.put("computedAt", Instant.now().toString());
        o.set("data", data);
        return o;
    }

    private ObjectNode error(String message) {
        ObjectNode o = mapper.createObjectNode();
        o.put("error", message);
        return o;
    }
}
