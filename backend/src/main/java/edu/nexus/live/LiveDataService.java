package edu.nexus.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.nexus.mumbai.EventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Ingestion layer for the sources that genuinely change over time. Runs on a schedule, separate from user requests:
 * API calls read the cached, validated result and never wait for an external provider.
 *
 * <ul>
 *   <li>Open-Meteo current weather for Mumbai (live model analysis, 15-minute steps), every 15 minutes.</li>
 *   <li>OpenStreetMap road edits since the committed snapshot (Overpass {@code newer:} query), every 60 minutes.</li>
 *   <li>OpenStreetMap map notes (existing {@link EventService} import), every 60 minutes.</li>
 * </ul>
 */
@Service
public class LiveDataService {

    private static final Logger log = LoggerFactory.getLogger(LiveDataService.class);
    static final String UA = "nexus-resilience-academic/3.0 (student project; OpenStreetMap and Open-Meteo data)";
    static final String WEATHER_URL = "https://api.open-meteo.com/v1/forecast?latitude=19.076&longitude=72.8777"
            + "&current=temperature_2m,relative_humidity_2m,precipitation,rain,wind_speed_10m,weather_code"
            + "&hourly=precipitation&forecast_days=1&timezone=Asia%2FKolkata";
    static final String OVERPASS_URL = "https://overpass-api.de/api/interpreter";
    static final Set<String> ROAD_CLASSES = Set.of("motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
            "secondary", "secondary_link", "tertiary", "tertiary_link");

    /** Pluggable HTTP so tests can simulate success, errors and timeouts without the network. */
    @FunctionalInterface
    public interface Http {
        Response send(HttpRequest request) throws IOException, InterruptedException;
    }

    public record Response(int status, String body) {}

    public record Weather(String observedAtLocal, String observedAtUtc, double temperatureC, int humidityPct, double precipitationMm,
                          double rainMm, double windKph, int weatherCode, String description, double next6hPrecipitationMm) {}

    /** geometry: flat [lat, lon, ...] (at most 60 points) or null when Overpass returned none. */
    public record RoadEdit(long wayId, String name, String highway, int version, String editedAt, long changeset, String osmUrl,
                           double[] geometry) {}

    public record SourceStatus(String id, String name, String owner, String endpoint, String licence, String coverage,
                               String updateFrequency, String kind, String status, String lastAttemptAt, String lastSuccessAt,
                               String observedAt, Long ageMinutes, String error, int records, String notes) {}

    public record LiveStatus(String serverTime, List<SourceStatus> sources, Weather weather, List<RoadEdit> roadEdits,
                             String roadEditsSince) {}

    /** Mutable per-source state (one writer: the scheduler). */
    static final class State {
        volatile Instant lastAttempt;
        volatile Instant lastSuccess;
        volatile Instant observed;
        volatile String error;
        volatile int records;
    }

    private final ObjectMapper mapper;
    private final EventService events;
    private final Http http;
    private final Clock clock;
    private final String snapshotTimestamp;
    private final boolean enabled;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private volatile Weather weather;
    private volatile List<RoadEdit> edits = List.of();

    @org.springframework.beans.factory.annotation.Autowired
    public LiveDataService(ObjectMapper mapper, EventService events, edu.nexus.mumbai.MumbaiService mumbai,
                           @Value("${nexus.live.enabled:true}") boolean enabled) {
        this(mapper, events, defaultHttp(), Clock.systemUTC(), text(mumbai.network().provenance(), "osmDataTimestamp"), enabled);
    }

    LiveDataService(ObjectMapper mapper, EventService events, Http http, Clock clock, String snapshotTimestamp, boolean enabled) {
        this.mapper = mapper;
        this.events = events;
        this.http = http;
        this.clock = clock;
        this.snapshotTimestamp = snapshotTimestamp == null ? "2026-11-20T16:43:50Z" : snapshotTimestamp;
        this.enabled = enabled;
        for (String id : List.of("weather", "osm-edits", "osm-notes")) {
            states.put(id, new State());
        }
    }

    private static Http defaultHttp() {
        HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
        return req -> {
            HttpResponse<String> r = c.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(r.statusCode(), r.body());
        };
    }

    // ------------------------------------------------------------------ schedule

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (enabled) {
            CompletableFuture.runAsync(() -> {
                refreshWeather();
                refreshRoadEdits();
                refreshNotes();
            });
        }
    }

    @Scheduled(initialDelayString = "PT15M", fixedDelayString = "PT15M")
    public void scheduledWeather() {
        if (enabled) {
            refreshWeather();
        }
    }

    @Scheduled(initialDelayString = "PT60M", fixedDelayString = "PT60M")
    public void scheduledEditsAndNotes() {
        if (enabled) {
            refreshRoadEdits();
            refreshNotes();
        }
    }

    // ------------------------------------------------------------------ sources

    public void refreshWeather() {
        State s = states.get("weather");
        s.lastAttempt = clock.instant();
        try {
            Response r = fetch(HttpRequest.newBuilder(URI.create(WEATHER_URL)).timeout(Duration.ofSeconds(20))
                    .header("User-Agent", UA).GET().build());
            Weather w = parseWeather(mapper.readTree(r.body()));
            weather = w;
            s.observed = Instant.parse(w.observedAtUtc());
            s.lastSuccess = clock.instant();
            s.records = 1;
            s.error = null;
        } catch (Exception e) {
            s.error = message(e);
            log.warn("weather ingestion failed: {}", s.error);
        }
    }

    public void refreshRoadEdits() {
        State s = states.get("osm-edits");
        s.lastAttempt = clock.instant();
        String q = "[out:json][timeout:60];way[\"highway\"~\"^(" + String.join("|", ROAD_CLASSES.stream().sorted().toList())
                + ")$\"](newer:\"" + snapshotTimestamp + "\")(18.89,72.77,19.29,72.99);out tags meta geom;";
        try {
            Response r = fetch(HttpRequest.newBuilder(URI.create(OVERPASS_URL)).timeout(Duration.ofSeconds(90))
                    .header("User-Agent", UA).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(q, StandardCharsets.UTF_8))).build());
            JsonNode root = mapper.readTree(r.body());
            edits = parseEdits(root);
            String base = root.path("osm3s").path("timestamp_osm_base").asText(null);
            s.observed = base == null ? null : Instant.parse(base);
            s.lastSuccess = clock.instant();
            s.records = edits.size();
            s.error = null;
        } catch (Exception e) {
            s.error = message(e);
            log.warn("OSM edit ingestion failed: {}", s.error);
        }
    }

    public void refreshNotes() {
        State s = states.get("osm-notes");
        s.lastAttempt = clock.instant();
        try {
            var r = events.refresh();
            if ("OK".equals(r.status())) {
                s.lastSuccess = clock.instant();
                s.observed = s.lastSuccess;
                s.records = r.fetched();
                s.error = null;
            } else {
                s.error = r.message();
            }
        } catch (Exception e) {
            s.error = message(e);
        }
    }

    /** GET/POST with at most two retries (2 s, then 4 s) on 429, 5xx and I/O errors; other statuses fail at once. */
    Response fetch(HttpRequest req) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                Thread.sleep(sleepMillis(attempt));
            }
            try {
                Response r = http.send(req);
                if (r.status() == 200) {
                    return r;
                }
                last = new IOException("HTTP " + r.status() + " from " + req.uri().getHost());
                if (r.status() != 429 && r.status() < 500) {
                    throw last;
                }
            } catch (java.net.http.HttpTimeoutException e) {
                last = new IOException("timeout contacting " + req.uri().getHost(), e);
            }
        }
        throw last;
    }

    long sleepMillis(int attempt) {
        return 2000L << (attempt - 1);
    }

    // ------------------------------------------------------------------ parsing and validation

    Weather parseWeather(JsonNode root) {
        JsonNode c = root.path("current");
        double lat = root.path("latitude").asDouble(Double.NaN);
        double lon = root.path("longitude").asDouble(Double.NaN);
        if (!(lat > 18.8 && lat < 19.4 && lon > 72.7 && lon < 73.1)) {
            throw new IllegalArgumentException("weather response is not for Mumbai");
        }
        if (!c.hasNonNull("time") || !c.hasNonNull("temperature_2m")) {
            throw new IllegalArgumentException("weather response has no current observation");
        }
        int offset = root.path("utc_offset_seconds").asInt(19800);
        LocalDateTime local = LocalDateTime.parse(c.get("time").asText());
        Instant utc = local.toInstant(ZoneOffset.ofTotalSeconds(offset));
        double t = c.get("temperature_2m").asDouble();
        double p = c.path("precipitation").asDouble(0);
        double rain = c.path("rain").asDouble(0);
        double wind = c.path("wind_speed_10m").asDouble(0);
        int hum = c.path("relative_humidity_2m").asInt(-1);
        if (t < -10 || t > 55 || p < 0 || rain < 0 || wind < 0 || wind > 300 || hum < 0 || hum > 100) {
            throw new IllegalArgumentException("weather values out of physical range");
        }
        double next6 = 0;
        JsonNode times = root.path("hourly").path("time");
        JsonNode precip = root.path("hourly").path("precipitation");
        for (int i = 0; i < times.size() && i < precip.size(); i++) {
            LocalDateTime h = LocalDateTime.parse(times.get(i).asText());
            if (!h.isBefore(local.withMinute(0)) && h.isBefore(local.plusHours(6))) {
                next6 += Math.max(0, precip.get(i).asDouble(0));
            }
        }
        int code = c.path("weather_code").asInt(-1);
        return new Weather(local.toString(), utc.toString(), t, hum, p, rain, wind, code, wmo(code), Math.round(next6 * 10) / 10.0);
    }

    List<RoadEdit> parseEdits(JsonNode root) {
        if (!root.has("elements")) {
            throw new IllegalArgumentException("Overpass response has no elements (probably an error page)");
        }
        List<RoadEdit> out = new ArrayList<>();
        for (JsonNode e : root.get("elements")) {
            String hw = e.path("tags").path("highway").asText("");
            if (!"way".equals(e.path("type").asText()) || !ROAD_CLASSES.contains(hw) || !e.hasNonNull("timestamp")) {
                continue;
            }
            Instant ts = Instant.parse(e.get("timestamp").asText()); // validates the format
            long id = e.get("id").asLong();
            JsonNode tags = e.path("tags");
            String name = tags.path("name").asText(tags.path("ref").asText(""));
            // user names and user ids are deliberately not stored
            double[] geom = null;
            JsonNode g = e.path("geometry");
            if (g.isArray() && g.size() >= 2) {
                int step = Math.max(1, (g.size() + 59) / 60);
                List<Double> pts = new ArrayList<>();
                for (int i = 0; i < g.size(); i += step) {
                    addPoint(pts, g.get(i));
                }
                if ((g.size() - 1) % step != 0) {
                    addPoint(pts, g.get(g.size() - 1));
                }
                geom = pts.stream().mapToDouble(Double::doubleValue).toArray();
            }
            out.add(new RoadEdit(id, name, hw, e.path("version").asInt(0), ts.toString(), e.path("changeset").asLong(0),
                    "https://www.openstreetmap.org/way/" + id + "/history", geom));
        }
        out.sort(Comparator.comparing(RoadEdit::editedAt).reversed());
        return List.copyOf(out);
    }

    private static void addPoint(List<Double> pts, JsonNode p) {
        double lat = p.path("lat").asDouble(Double.NaN);
        double lon = p.path("lon").asDouble(Double.NaN);
        if (lat > 18.5 && lat < 19.6 && lon > 72.5 && lon < 73.3) { // drop malformed or far-away points
            pts.add(lat);
            pts.add(lon);
        }
    }

    static String wmo(int code) {
        return switch (code) {
            case 0 -> "clear sky";
            case 1, 2, 3 -> "partly cloudy";
            case 45, 48 -> "fog";
            case 51, 53, 55, 56, 57 -> "drizzle";
            case 61, 63, 65, 66, 67 -> "rain";
            case 80, 81, 82 -> "rain showers";
            case 95, 96, 99 -> "thunderstorm";
            default -> code < 0 ? "unknown" : "WMO code " + code;
        };
    }

    // ------------------------------------------------------------------ read side (cheap, never calls providers)

    public LiveStatus status() {
        Instant now = clock.instant();
        List<SourceStatus> list = List.of(
                source("weather", "Current weather, Mumbai", "Open-Meteo (open-meteo.com)", "api.open-meteo.com/v1/forecast",
                        "CC BY 4.0, free non-commercial use", "One grid point near 19.08 N, 72.85 E", "every 15 min", "LIVE",
                        Duration.ofMinutes(15), now, "Model analysis/forecast, not a rain gauge reading. Shown for context only; "
                                + "it does not change any route or score."),
                source("osm-edits", "Road edits since the snapshot", "OpenStreetMap contributors via Overpass API",
                        "overpass-api.de/api/interpreter (newer: query)", "ODbL 1.0", "Greater Mumbai bbox, motorway to tertiary",
                        "every 60 min", "PERIODIC", Duration.ofMinutes(60), now,
                        "Ways edited in OSM after " + snapshotTimestamp + ". Lists real public edits; they are not yet in the routing "
                                + "graph until the snapshot is rebuilt."),
                source("osm-notes", "Map-issue notes", "OpenStreetMap Notes API", "api.openstreetmap.org/api/0.6/notes",
                        "ODbL 1.0", "Greater Mumbai bbox", "every 60 min", "PERIODIC", Duration.ofMinutes(60), now,
                        "Community map-issue reports, not traffic incidents."),
                new SourceStatus("traffic", "Live traffic", null, null, null, null, null, "NOT_AVAILABLE", "NOT_AVAILABLE",
                        null, null, null, null, null, 0, "No free, documented, terms-verified Mumbai traffic feed; a licensed "
                        + "provider key would be needed."));
        return new LiveStatus(now.toString(), list, weather, edits, snapshotTimestamp);
    }

    private SourceStatus source(String id, String name, String owner, String endpoint, String licence, String coverage, String freq,
                                String kind, Duration period, Instant now, String notes) {
        State s = states.get(id);
        String status;
        if (!enabled) {
            status = "DISABLED";
        } else if (s.lastSuccess == null) {
            status = s.lastAttempt == null ? "NOT_YET_FETCHED" : "ERROR";
        } else if (Duration.between(s.lastSuccess, now).compareTo(period.multipliedBy(3)) > 0) {
            status = "STALE";
        } else {
            status = s.error == null ? "OK" : "STALE";
        }
        Long age = s.observed == null ? null : Duration.between(s.observed, now).toMinutes();
        return new SourceStatus(id, name, owner, endpoint, licence, coverage, freq, kind, status, str(s.lastAttempt),
                str(s.lastSuccess), str(s.observed), age, s.error, s.records, notes);
    }

    public Weather weather() {
        return weather;
    }

    public List<RoadEdit> roadEdits() {
        return edits;
    }

    private static String str(Instant i) {
        return i == null ? null : i.toString();
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return (m == null ? e.getClass().getSimpleName() : m).replaceAll("\\s+", " ");
    }

    private static String text(JsonNode n, String f) {
        return n == null || n.get(f) == null ? null : n.get(f).asText();
    }
}
