package edu.nexus.mumbai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.nexus.algorithms.graph.RoadGraph;
import edu.nexus.model.ValidationException;
import edu.nexus.mumbai.MumbaiDtos.PlaceMatch;
import edu.nexus.mumbai.MumbaiDtos.RoadMatch;
import edu.nexus.mumbai.MumbaiDtos.SearchResponse;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Location search.
 * <ul>
 *   <li><b>Road names</b> (search-as-you-type): case-insensitive match over the road names in the OSM snapshot,
 *       answered locally with no external call.</li>
 *   <li><b>Places</b> (explicit search only): OpenStreetMap Nominatim, bounded to the study area. Its usage policy
 *       forbids autocomplete and allows at most 1 request per second, so this is called only when the user presses
 *       Search, is globally rate-limited, cached, and sends an identifying User-Agent.</li>
 * </ul>
 */
@Service
public class SearchService {

    private static final String NOMINATIM = "https://nominatim.openstreetmap.org/search";
    private static final int CACHE_SIZE = 300;

    private final MumbaiNetwork net;
    private final ObjectMapper mapper;
    private final Map<String, RoadMatch> roads = new LinkedHashMap<>();
    private final Map<String, List<PlaceMatch>> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<PlaceMatch>> e) {
            return size() > CACHE_SIZE;
        }
    };
    private long lastCallMs;

    public SearchService(MumbaiService mumbai, ObjectMapper mapper) {
        this.net = mumbai.network();
        this.mapper = mapper;
        RoadGraph g = net.graph();
        Map<String, double[]> agg = new HashMap<>(); // name -> [totalM, segments, bestLen, lat, lon]
        Map<String, String> cls = new HashMap<>();
        for (int e = 0; e < g.segmentCount(); e++) {
            String n = net.name(e);
            if (n.isBlank()) {
                continue;
            }
            double[] a = agg.computeIfAbsent(n, k -> new double[5]);
            a[0] += g.lengthM(e);
            a[1]++;
            if (g.lengthM(e) > a[2]) {
                double[] gm = net.geometry(e);
                int mid = (gm.length / 4) * 2;
                a[2] = g.lengthM(e);
                a[3] = gm[mid];
                a[4] = gm[mid + 1];
                cls.put(n, net.roadClass(e));
            }
        }
        agg.entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey)).forEach(en -> {
            double[] a = en.getValue();
            roads.put(en.getKey(), new RoadMatch(en.getKey(), cls.get(en.getKey()), a[3], a[4], Math.round(a[0] / 100) / 10.0, (int) a[1]));
        });
    }

    public SearchResponse search(String q, boolean places) {
        if (q == null || q.trim().length() < 2) {
            throw new ValidationException("q", "type at least 2 characters");
        }
        String query = q.trim();
        if (query.length() > 100) {
            throw new ValidationException("q", "at most 100 characters");
        }
        String needle = query.toLowerCase(Locale.ROOT);
        List<RoadMatch> starts = new ArrayList<>();
        List<RoadMatch> contains = new ArrayList<>();
        for (RoadMatch r : roads.values()) {
            String n = r.name().toLowerCase(Locale.ROOT);
            if (n.startsWith(needle)) {
                starts.add(r);
            } else if (n.contains(needle)) {
                contains.add(r);
            }
        }
        starts.sort(Comparator.comparingDouble(RoadMatch::totalKm).reversed());
        contains.sort(Comparator.comparingDouble(RoadMatch::totalKm).reversed());
        List<RoadMatch> out = new ArrayList<>(starts);
        out.addAll(contains);
        List<RoadMatch> top = out.subList(0, Math.min(8, out.size()));
        if (!places) {
            return new SearchResponse(query, List.copyOf(top), List.of(), "not requested");
        }
        try {
            return new SearchResponse(query, List.copyOf(top), geocode(query), "OK (OpenStreetMap Nominatim)");
        } catch (RuntimeException e) {
            return new SearchResponse(query, List.copyOf(top), List.of(), "place search unavailable: " + e.getMessage());
        }
    }

    private synchronized List<PlaceMatch> geocode(String query) {
        String key = query.toLowerCase(Locale.ROOT);
        List<PlaceMatch> hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        long wait = 1100 - (System.currentTimeMillis() - lastCallMs);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
        }
        lastCallMs = System.currentTimeMillis();
        MumbaiDtos.Area a = MumbaiService.STUDY_AREA;
        String url = NOMINATIM + "?format=jsonv2&limit=5&countrycodes=in&bounded=1&viewbox=" + a.west() + "," + a.north() + ","
                + a.east() + "," + a.south() + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> resp = client.send(HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "nexus-mumbai-road-network-intelligence/2.0 (academic project)")
                    .timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("Nominatim answered HTTP " + resp.statusCode());
            }
            JsonNode arr = mapper.readTree(resp.body());
            List<PlaceMatch> out = new ArrayList<>();
            for (JsonNode n : arr) {
                out.add(new PlaceMatch(n.path("display_name").asText(), n.path("lat").asDouble(), n.path("lon").asDouble(),
                        n.path("type").asText()));
            }
            cache.put(key, out);
            return out;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not reach Nominatim");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }

    public int roadNameCount() {
        return roads.size();
    }
}
