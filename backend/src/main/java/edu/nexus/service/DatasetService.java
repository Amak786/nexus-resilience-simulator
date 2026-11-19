package edu.nexus.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.nexus.model.CityDataset;
import edu.nexus.model.CityNode;
import edu.nexus.model.DatasetMeta;
import edu.nexus.model.NodeType;
import edu.nexus.model.Road;
import edu.nexus.model.ValidationException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Loads the bundled synthetic dataset, summarises datasets and imports CSV files. */
@Service
public class DatasetService {

    public static final int MAX_CSV_CHARS = 1_000_000;

    public record DatasetSummary(String name, boolean synthetic, int nodes, int depots, int junctions, int demandZones,
                                 int roads, int oneWayRoads, int incidents, int timelineHours, long totalSupply,
                                 long totalDemand, long totalRoadCapacity, double totalRoadKm) {}

    public record CsvImportRequest(String nodesCsv, String roadsCsv) {}

    public record ImportResult(CityDataset dataset, DatasetSummary summary) {}

    private final ObjectMapper mapper;

    public DatasetService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public CityDataset demo() {
        try (InputStream in = new ClassPathResource("data/varunapuri-demo.json").getInputStream()) {
            CityDataset ds = mapper.readValue(in, CityDataset.class);
            DatasetValidator.validate(ds);
            return ds;
        } catch (IOException e) {
            throw new IllegalStateException("bundled demo dataset is unreadable", e);
        }
    }

    public DatasetSummary summarize(CityDataset ds) {
        DatasetValidator.validate(ds);
        int depots = 0;
        int junctions = 0;
        int zones = 0;
        long supply = 0;
        long demand = 0;
        for (CityNode n : ds.nodes()) {
            if (n.type() == NodeType.DEPOT) {
                depots++;
                supply += n.supply();
            } else if (n.type() == NodeType.JUNCTION) {
                junctions++;
            } else {
                zones++;
                demand += n.demand();
            }
        }
        List<Road> roads = ds.roads() == null ? List.of() : ds.roads();
        long cap = roads.stream().mapToLong(Road::capacity).sum();
        double km = Math.round(roads.stream().mapToDouble(Road::lengthKm).sum() * 10) / 10.0;
        int oneWay = (int) roads.stream().filter(Road::oneWayOnly).count();
        String name = ds.meta() == null || ds.meta().name() == null ? "Untitled dataset" : ds.meta().name();
        boolean synthetic = ds.meta() != null && Boolean.TRUE.equals(ds.meta().synthetic());
        int hours = ds.demandTimeline() == null ? 0 : ds.demandTimeline().hourlyDemand().size();
        return new DatasetSummary(name, synthetic, ds.nodes().size(), depots, junctions, zones, roads.size(), oneWay,
                ds.incidents() == null ? 0 : ds.incidents().size(), hours, supply, demand, cap, km);
    }

    /**
     * Builds a dataset from two CSV texts.
     * nodes: {@code id,name,type,x,y,supply,demand,severity,vulnerablePercent,population}
     * roads: {@code id,from,to,capacity,lengthKm,oneWay}. Empty cells mean "absent". Quoted fields are supported.
     */
    public ImportResult importCsv(CsvImportRequest req) {
        if (req == null || req.nodesCsv() == null || req.roadsCsv() == null) {
            throw new ValidationException("csv", "both nodesCsv and roadsCsv are required");
        }
        if (req.nodesCsv().length() > MAX_CSV_CHARS || req.roadsCsv().length() > MAX_CSV_CHARS) {
            throw new ValidationException("csv", "each CSV must be at most " + MAX_CSV_CHARS + " characters");
        }
        List<List<String>> nodeRows = Csv.parse(req.nodesCsv(), "nodesCsv");
        List<List<String>> roadRows = Csv.parse(req.roadsCsv(), "roadsCsv");
        Csv.requireHeader(nodeRows, List.of("id", "name", "type", "x", "y", "supply", "demand", "severity",
                "vulnerablePercent", "population"), "nodesCsv");
        Csv.requireHeader(roadRows, List.of("id", "from", "to", "capacity", "lengthKm", "oneWay"), "roadsCsv");
        List<CityNode> nodes = new ArrayList<>();
        for (int i = 1; i < nodeRows.size(); i++) {
            List<String> r = nodeRows.get(i);
            String f = "nodesCsv line " + (i + 1);
            NodeType type;
            try {
                type = NodeType.valueOf(r.get(2).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new ValidationException(f + ".type", "must be DEPOT, JUNCTION or DEMAND_ZONE");
            }
            nodes.add(new CityNode(r.get(0).trim(), r.get(1).trim(), type, Csv.dbl(r.get(3), f + ".x"),
                    Csv.dbl(r.get(4), f + ".y"), Csv.lng(r.get(5), f + ".supply"), Csv.lng(r.get(6), f + ".demand"),
                    Csv.integer(r.get(7), f + ".severity"), Csv.integer(r.get(8), f + ".vulnerablePercent"),
                    Csv.lng(r.get(9), f + ".population")));
        }
        List<Road> roads = new ArrayList<>();
        for (int i = 1; i < roadRows.size(); i++) {
            List<String> r = roadRows.get(i);
            String f = "roadsCsv line " + (i + 1);
            String ow = r.get(5).trim().toLowerCase(Locale.ROOT);
            if (!ow.isEmpty() && !ow.equals("true") && !ow.equals("false")) {
                throw new ValidationException(f + ".oneWay", "must be true, false or empty");
            }
            roads.add(new Road(r.get(0).trim(), r.get(1).trim(), r.get(2).trim(), Csv.lng(r.get(3), f + ".capacity"),
                    Csv.dbl(r.get(4), f + ".lengthKm"), ow.isEmpty() ? null : Boolean.valueOf(ow)));
        }
        CityDataset ds = new CityDataset(new DatasetMeta("Imported CSV dataset", "Imported by the user from CSV files.",
                null, "user-defined", "csv"), nodes, roads, List.of(), null);
        return new ImportResult(ds, summarize(ds));
    }

    /** Minimal RFC 4180 CSV reader (quotes, escaped quotes, CRLF). */
    static final class Csv {

        private Csv() {}

        static List<List<String>> parse(String text, String field) {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                            cell.append('"');
                            i++;
                        } else {
                            quoted = false;
                        }
                    } else {
                        cell.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    row.add(cell.toString());
                    cell.setLength(0);
                    if (!(row.size() == 1 && row.get(0).isBlank())) {
                        rows.add(row);
                    }
                    row = new ArrayList<>();
                } else {
                    cell.append(c);
                }
            }
            if (quoted) {
                throw new ValidationException(field, "unterminated quoted field");
            }
            row.add(cell.toString());
            if (!(row.size() == 1 && row.get(0).isBlank())) {
                rows.add(row);
            }
            if (rows.size() < 2) {
                throw new ValidationException(field, "needs a header row and at least one data row");
            }
            int width = rows.get(0).size();
            for (int i = 1; i < rows.size(); i++) {
                if (rows.get(i).size() != width) {
                    throw new ValidationException(field + " line " + (i + 1), "has " + rows.get(i).size() + " columns, expected " + width);
                }
            }
            return rows;
        }

        static void requireHeader(List<List<String>> rows, List<String> expected, String field) {
            List<String> header = rows.get(0).stream().map(String::trim).toList();
            if (!header.equals(expected)) {
                throw new ValidationException(field, "header must be exactly: " + String.join(",", expected));
            }
        }

        static Double dbl(String s, String field) {
            if (s.isBlank()) {
                return null;
            }
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw new ValidationException(field, "'" + s + "' is not a number");
            }
        }

        static Long lng(String s, String field) {
            if (s.isBlank()) {
                return null;
            }
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw new ValidationException(field, "'" + s + "' is not a whole number");
            }
        }

        static Integer integer(String s, String field) {
            Long v = lng(s, field);
            if (v != null && (v > Integer.MAX_VALUE || v < Integer.MIN_VALUE)) {
                throw new ValidationException(field, "is out of range");
            }
            return v == null ? null : v.intValue();
        }
    }
}
