package edu.nexus.analyst;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.nexus.integrity.IntegrityDtos.Change;
import edu.nexus.integrity.IntegrityDtos.IntegrityRequest;
import edu.nexus.integrity.IntegrityDtos.IntegrityResponse;
import edu.nexus.integrity.IntegrityService;
import edu.nexus.live.LiveDataService;
import edu.nexus.model.ValidationException;
import edu.nexus.mumbai.MumbaiDtos.Area;
import edu.nexus.mumbai.MumbaiDtos.ProvenanceResponse;
import edu.nexus.mumbai.MumbaiDtos.RouteRequest;
import edu.nexus.mumbai.MumbaiDtos.RouteResponse;
import edu.nexus.mumbai.MumbaiService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only way the AI analyst can learn anything: each tool runs real application logic and returns a small, labelled
 * JSON result. The model never sees the raw dataset and cannot invent a measurement the tools did not return.
 */
@Component
public class AnalystTools {

    /** Named analysis areas with default probe routes (same as the Integrity Check page). */
    public static final Map<String, double[]> AREAS = new LinkedHashMap<>();

    static {
        AREAS.put("dadar", new double[] {19.01, 72.83, 19.06, 72.87, 19.018, 72.847, 19.056, 72.845});
        AREAS.put("kurla", new double[] {19.05, 72.87, 19.10, 72.92, 19.065, 72.88, 19.09, 72.91});
        AREAS.put("andheri", new double[] {19.10, 72.83, 19.15, 72.88, 19.107, 72.84, 19.14, 72.87});
        AREAS.put("fort", new double[] {18.90, 72.81, 18.98, 72.85, 18.915, 72.828, 18.975, 72.835});
        AREAS.put("borivali", new double[] {19.18, 72.83, 19.25, 72.87, 19.185, 72.845, 19.24, 72.855});
    }

    /** Tool definition: name, description, JSON-schema properties and required fields. */
    public record ToolSpec(String name, String description, Map<String, Object> properties, List<String> required) {}

    public static final List<ToolSpec> SPECS = List.of(
            new ToolSpec("get_data_status",
                    "Freshness and health of every data source: last successful update, observation time, errors, record counts. "
                            + "Call this before making any claim about how current the data is.", Map.of(), List.of()),
            new ToolSpec("get_network_summary",
                    "Statistics of the road graph actually loaded (junctions, segments, km, one-way share, weak components, "
                            + "attribute coverage such as how many segments lack lanes or speed tags).", Map.of(), List.of()),
            new ToolSpec("get_recent_road_edits",
                    "Road ways edited in OpenStreetMap after the committed snapshot, from the latest live Overpass check "
                            + "(id, name, class, edit time, changeset). Empty list means no edits were found at the last check.",
                    Map.of("limit", Map.of("type", "integer", "description", "max edits to return, 1-30")), List.of()),
            new ToolSpec("get_weather",
                    "Latest Open-Meteo current conditions for Mumbai (model analysis, not a gauge). Context only.", Map.of(), List.of()),
            new ToolSpec("analyse_integrity",
                    "Run the integrity check for one area: compare the trusted 2025-11-20 OSM baseline with the current snapshot, "
                            + "with DSU connectivity, Dijkstra probe route, max-flow road-disjoint routes and ranked changes.",
                    Map.of("area", Map.of("type", "string", "enum", List.copyOf(AREAS.keySet()),
                            "description", "dadar, kurla, andheri, fort or borivali")), List.of("area")),
            new ToolSpec("find_route",
                    "Compute a route on the current road graph with Dijkstra (Fibonacci heap and PriorityQueue). metric DISTANCE uses "
                            + "real road length; ESTIMATED_TIME uses free-flow speed assumptions (not live traffic).",
                    Map.of("from_lat", Map.of("type", "number"), "from_lon", Map.of("type", "number"),
                            "to_lat", Map.of("type", "number"), "to_lon", Map.of("type", "number"),
                            "metric", Map.of("type", "string", "enum", List.of("DISTANCE", "ESTIMATED_TIME"))),
                    List.of("from_lat", "from_lon", "to_lat", "to_lon")));

    private final ObjectMapper mapper;
    private final MumbaiService mumbai;
    private final IntegrityService integrity;
    private final LiveDataService live;

    public AnalystTools(ObjectMapper mapper, MumbaiService mumbai, IntegrityService integrity, LiveDataService live) {
        this.mapper = mapper;
        this.mumbai = mumbai;
        this.integrity = integrity;
        this.live = live;
    }

    /** Runs a tool. Invalid input or an unknown tool returns {"error": ...} instead of throwing, so the model can recover. */
    public ObjectNode execute(String name, JsonNode input) {
        try {
            return switch (name) {
                case "get_data_status" -> wrap("data status", "application ingestion log", mapper.valueToTree(live.status()));
                case "get_network_summary" -> networkSummary();
                case "get_recent_road_edits" -> recentEdits(input);
                case "get_weather" -> live.weather() == null
                        ? error("no weather observation has been ingested yet (source not reachable or not fetched)")
                        : wrap("Open-Meteo current conditions", "Open-Meteo, CC BY 4.0", mapper.valueToTree(live.weather()));
                case "analyse_integrity" -> integritySummary(input);
                case "find_route" -> route(input);
                default -> error("unknown tool " + name);
            };
        } catch (ValidationException e) {
            return error(e.getMessage());
        } catch (RuntimeException e) {
            return error("tool failed: " + e.getClass().getSimpleName());
        }
    }

    private ObjectNode networkSummary() {
        ProvenanceResponse p = mumbai.provenance();
        ObjectNode n = mapper.createObjectNode();
        n.set("graph", mapper.valueToTree(p.graph()));
        n.put("liveTraffic", p.liveTrafficStatus());
        n.put("snapshotOsmTimestamp", mumbai.network().provenance().path("osmDataTimestamp").asText());
        return wrap("road graph statistics", "OpenStreetMap snapshot (ODbL) + derived statistics", n);
    }

    private ObjectNode recentEdits(JsonNode input) {
        int limit = input == null || !input.has("limit") ? 15 : Math.max(1, Math.min(30, input.get("limit").asInt(15)));
        List<LiveDataService.RoadEdit> all = live.roadEdits();
        ObjectNode n = mapper.createObjectNode();
        n.put("editsSince", live.status().roadEditsSince());
        n.put("total", all.size());
        n.set("edits", mapper.valueToTree(all.subList(0, Math.min(limit, all.size()))));
        var src = live.status().sources().stream().filter(s -> s.id().equals("osm-edits")).findFirst().orElseThrow();
        n.put("sourceStatus", src.status());
        n.put("lastSuccessfulCheck", src.lastSuccessAt());
        return wrap("OSM road edits after the snapshot", "OpenStreetMap via Overpass (ODbL)", n);
    }

    private ObjectNode integritySummary(JsonNode input) {
        String key = input == null ? "" : input.path("area").asText("");
        double[] a = AREAS.get(key);
        if (a == null) {
            return error("area must be one of " + AREAS.keySet());
        }
        IntegrityResponse r = integrity.analyse(new IntegrityRequest(new Area(a[0], a[1], a[2], a[3]), a[4], a[5], a[6], a[7],
                List.of(), List.of(), 5, null, null, false));
        ObjectNode n = mapper.createObjectNode();
        n.put("area", key);
        n.put("totalChanges", r.totalChanges());
        n.set("statusCounts", mapper.valueToTree(r.statusCounts()));
        ObjectNode ba = n.putObject("beforeAfter");
        for (var side : List.of(Map.entry("baseline", r.baseline()), Map.entry("updated", r.updated()))) {
            ObjectNode s = ba.putObject(side.getKey());
            s.put("osmDate", side.getValue().snapshot().osmDate());
            s.put("ways", side.getValue().snapshot().ways());
            s.put("components", side.getValue().connectivity().components());
            s.put("largestShare", side.getValue().connectivity().largestShare());
            s.put("probeRouteKm", side.getValue().route().reachable() ? side.getValue().route().distanceM() / 1000 : -1);
            s.put("roadDisjointRoutes", side.getValue().flow().disjointRoutesEdmondsKarp());
            s.put("fibonacciMs", side.getValue().route().fibonacci().elapsedMs());
            s.put("priorityQueueMs", side.getValue().route().binary().elapsedMs());
        }
        ArrayNode top = n.putArray("topChanges");
        for (String id : r.ranking().topIds()) {
            Change c = r.changes().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
            ObjectNode o = top.addObject();
            o.put("id", c.id());
            o.put("road", c.name().isEmpty() ? "(unnamed)" : c.name());
            o.put("class", c.roadClass());
            o.set("kinds", mapper.valueToTree(c.kinds()));
            o.put("before", c.before());
            o.put("after", c.after());
            o.put("score", c.score());
            o.put("status", c.status());
            o.set("why", mapper.valueToTree(c.scoreBreakdown()));
            o.put("evidenceUrl", c.historyUrl());
        }
        n.set("scoringRule", mapper.valueToTree(r.scoringRule()));
        n.put("verifiedMaliciousNote", r.verifiedMaliciousNote());
        return wrap("integrity check, " + key, "OSM 2025-11-20 baseline vs current snapshot (ODbL); derived results", n);
    }

    private ObjectNode route(JsonNode in) {
        for (String f : List.of("from_lat", "from_lon", "to_lat", "to_lon")) {
            if (in == null || !in.path(f).isNumber()) {
                return error(f + " must be a number");
            }
        }
        String metric = in.path("metric").asText("DISTANCE");
        RouteResponse r = mumbai.route(new RouteRequest(in.get("from_lat").asDouble(), in.get("from_lon").asDouble(),
                in.get("to_lat").asDouble(), in.get("to_lon").asDouble(), metric, List.of()));
        ObjectNode n = mapper.createObjectNode();
        n.put("reachable", r.reachable());
        n.put("metric", r.metric());
        n.put("distanceKm", Math.round(r.distanceM()) / 1000.0);
        n.put("estimatedFreeFlowMinutes", Math.round(r.estimatedTimeS() / 6.0) / 10.0);
        n.put("maxspeedTaggedSharePct", r.maxspeedTaggedSharePct());
        n.put("snapFromM", r.from().snapDistanceM());
        n.put("snapToM", r.to().snapDistanceM());
        ArrayNode roads = n.putArray("roads");
        r.steps().stream().limit(15).forEach(s -> roads.add(s.name() + " (" + Math.round(s.lengthM()) + " m)"));
        n.put("fibonacciMs", r.fibonacci().elapsedMs());
        n.put("priorityQueueMs", r.binary().elapsedMs());
        n.put("queuesAgree", r.queuesAgree());
        n.set("explanation", mapper.valueToTree(r.explanation()));
        return wrap("Dijkstra route", "OpenStreetMap snapshot (ODbL); derived result", n);
    }

    private ObjectNode wrap(String what, String source, JsonNode data) {
        ObjectNode o = mapper.createObjectNode();
        o.put("result", what);
        o.put("source", source);
        o.put("computedAt", java.time.Instant.now().toString());
        o.set("data", data);
        return o;
    }

    private ObjectNode error(String message) {
        ObjectNode o = mapper.createObjectNode();
        o.put("error", message);
        return o;
    }
}
