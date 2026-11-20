package edu.nexus.mumbai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.nexus.algorithms.graph.RoadGraph;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The Mumbai road-network snapshot built by {@code data-pipeline/build-mumbai-snapshot.mjs} from
 * OpenStreetMap. Nodes are intersections and way endpoints; segments are road pieces between them.
 *
 * <p>Three per-segment quantities are kept separate:
 * <ul>
 *   <li>{@code lengthM}: static geographic length (haversine over the OSM geometry);</li>
 *   <li>{@code travelTimeS}: DERIVED ESTIMATE = length / speed, speed = OSM maxspeed tag or class default;</li>
 *   <li>{@code capacityVph}: MODELLED PROXY = lanes per direction x per-lane capacity by class.</li>
 * </ul>
 */
public final class MumbaiNetwork {

    public record ClassDefault(double speedKph, int lanesPerDirection, double perLaneVph) {}

    private final JsonNode provenance;
    private final JsonNode assumptions;
    private final long[] osmNodeId;
    private final double[] lat;
    private final double[] lon;
    private final long[] wayId;
    private final String[] roadClass;
    private final String[] name;
    private final int[] lanesTag;
    private final int[] maxspeedTag;
    private final double[][] geometry;
    private final RoadGraph graph;

    private MumbaiNetwork(JsonNode root) {
        provenance = root.get("provenance");
        assumptions = root.get("assumptions");
        JsonNode nodes = root.get("nodes");
        int n = nodes.get("osmId").size();
        osmNodeId = new long[n];
        lat = new double[n];
        lon = new double[n];
        for (int i = 0; i < n; i++) {
            osmNodeId[i] = nodes.get("osmId").get(i).asLong();
            lat[i] = nodes.get("lat").get(i).asDouble();
            lon[i] = nodes.get("lon").get(i).asDouble();
        }
        List<String> classes = new ArrayList<>();
        root.get("classes").forEach(c -> classes.add(c.asText()));
        List<String> names = new ArrayList<>();
        root.get("names").forEach(c -> names.add(c.asText()));
        JsonNode e = root.get("edges");
        int m = e.get("u").size();
        int[] u = new int[m];
        int[] v = new int[m];
        boolean[] oneWay = new boolean[m];
        double[] len = new double[m];
        double[] time = new double[m];
        double[] cap = new double[m];
        wayId = new long[m];
        roadClass = new String[m];
        name = new String[m];
        lanesTag = new int[m];
        maxspeedTag = new int[m];
        geometry = new double[m][];
        for (int i = 0; i < m; i++) {
            u[i] = e.get("u").get(i).asInt();
            v[i] = e.get("v").get(i).asInt();
            oneWay[i] = e.get("oneway").get(i).asInt() == 1;
            len[i] = e.get("lengthM").get(i).asDouble();
            wayId[i] = e.get("way").get(i).asLong();
            roadClass[i] = classes.get(e.get("cls").get(i).asInt());
            name[i] = names.get(e.get("name").get(i).asInt());
            lanesTag[i] = e.get("lanes").get(i).asInt();
            maxspeedTag[i] = e.get("maxspeed").get(i).asInt();
            JsonNode g = e.get("geom").get(i);
            geometry[i] = new double[g.size()];
            for (int k = 0; k < g.size(); k++) {
                geometry[i][k] = g.get(k).asDouble();
            }
            ClassDefault d = classDefault(roadClass[i]);
            double speed = maxspeedTag[i] > 0 ? maxspeedTag[i] : d.speedKph();
            time[i] = len[i] / (speed / 3.6);
            int lanesDir = lanesTag[i] > 0 ? (oneWay[i] ? lanesTag[i] : Math.max(1, (int) Math.round(lanesTag[i] / 2.0)))
                    : d.lanesPerDirection();
            cap[i] = lanesDir * d.perLaneVph();
        }
        graph = new RoadGraph(n, u, v, oneWay, len, time, cap);
    }

    public static MumbaiNetwork load(InputStream in, ObjectMapper mapper) throws IOException {
        return new MumbaiNetwork(mapper.readTree(in));
    }

    public ClassDefault classDefault(String cls) {
        JsonNode d = assumptions.get("classDefaults").get(cls);
        if (d == null) {
            throw new IllegalStateException("no default for road class " + cls);
        }
        return new ClassDefault(d.get("speed").asDouble(), d.get("lanes").asInt(), d.get("perLane").asDouble());
    }

    public RoadGraph graph() {
        return graph;
    }

    public JsonNode provenance() {
        return provenance;
    }

    public JsonNode assumptions() {
        return assumptions;
    }

    public int nodeCount() {
        return lat.length;
    }

    public double lat(int i) {
        return lat[i];
    }

    public double lon(int i) {
        return lon[i];
    }

    public long osmNodeId(int i) {
        return osmNodeId[i];
    }

    public long wayId(int e) {
        return wayId[e];
    }

    public String roadClass(int e) {
        return roadClass[e];
    }

    public String name(int e) {
        return name[e];
    }

    /** Raw OSM lanes tag, or -1 when absent. */
    public int lanesTag(int e) {
        return lanesTag[e];
    }

    /** Raw OSM maxspeed tag in km/h, or -1 when absent. */
    public int maxspeedTag(int e) {
        return maxspeedTag[e];
    }

    /** Flat [lat0, lon0, lat1, lon1, ...] geometry from segU to segV. */
    public double[] geometry(int e) {
        return geometry[e];
    }

    /** Index of the node closest to (lat, lon) using an equirectangular distance (fine at city scale). */
    public int nearestNode(double qLat, double qLon, boolean[] allowed) {
        double cos = Math.cos(Math.toRadians(qLat));
        int best = -1;
        double bestD = Double.POSITIVE_INFINITY;
        for (int i = 0; i < lat.length; i++) {
            if (allowed != null && !allowed[i]) {
                continue;
            }
            double dy = lat[i] - qLat;
            double dx = (lon[i] - qLon) * cos;
            double d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Great-circle distance in metres between a point and a node. */
    public double metresTo(int node, double qLat, double qLon) {
        double r = 6371008.8;
        double dLat = Math.toRadians(lat[node] - qLat);
        double dLon = Math.toRadians(lon[node] - qLon);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(qLat)) * Math.cos(Math.toRadians(lat[node])) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }
}
