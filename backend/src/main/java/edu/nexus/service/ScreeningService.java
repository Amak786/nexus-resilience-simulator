package edu.nexus.service;

import edu.nexus.algorithms.bloom.BloomFilter;
import edu.nexus.dto.IncidentDtos.ScreenRequest;
import edu.nexus.dto.IncidentDtos.ScreenResponse;
import edu.nexus.dto.IncidentDtos.ScreenedId;
import edu.nexus.model.Incident;
import edu.nexus.model.ValidationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Duplicate-incident screening: Bloom filter pre-check, then exact verification.
 *
 * <p>The exact registry ({@link HashSet}) plays the role of the authoritative record store (in a real
 * deployment, a database). The Bloom filter answers "definitely new" without touching it; every
 * "possibly present" answer is confirmed against it, so the final verdicts are exact.
 *
 * <p>The measured false-positive rate uses a separate set of generated probe ids that are verified
 * to be absent from the registry, so every positive among them is a genuine false positive.
 */
@Service
public class ScreeningService {

    public static final long MAX_EXPECTED = 2_000_000;
    public static final int MAX_REGISTERED = 50_000;
    public static final int MAX_GENERATED = 200_000;
    public static final int MAX_INCOMING = 1_000;
    public static final int MAX_PROBES = 200_000;

    public ScreenResponse screen(ScreenRequest req) {
        if (req == null) {
            throw new ValidationException("request", "is required");
        }
        long expected = Inputs.longParam(req.expectedInsertions(), 10_000, 1, MAX_EXPECTED, "expectedInsertions");
        double p = Inputs.doubleParam(req.falsePositiveRate(), 0.01, 1e-6, 0.5, "falsePositiveRate");
        long seed = Inputs.longParam(req.seed(), 2026L, 0, Long.MAX_VALUE, "seed");
        int generate = Inputs.intParam(req.generateHistorical(), 5_000, 0, MAX_GENERATED, "generateHistorical");
        int probes = Inputs.intParam(req.absentProbes(), 10_000, 0, MAX_PROBES, "absentProbes");
        List<String> registered = req.registeredIds() == null ? List.of() : req.registeredIds();
        List<String> incoming = req.incomingIds() == null ? List.of() : req.incomingIds();
        if (registered.size() > MAX_REGISTERED) {
            throw new ValidationException("registeredIds", "at most " + MAX_REGISTERED + " ids");
        }
        if (incoming.isEmpty()) {
            throw new ValidationException("incomingIds", "enter at least one incident id to screen");
        }
        if (incoming.size() > MAX_INCOMING) {
            throw new ValidationException("incomingIds", "at most " + MAX_INCOMING + " ids");
        }
        checkIds(registered, "registeredIds");
        checkIds(incoming, "incomingIds");

        List<String> registryOrder = new ArrayList<>();
        if (req.dataset() != null) {
            DatasetValidator.validate(req.dataset());
            if (req.dataset().incidents() != null) {
                for (Incident inc : req.dataset().incidents()) {
                    registryOrder.add(inc.id());
                }
            }
        }
        registryOrder.addAll(registered.stream().map(String::trim).toList());
        SplittableRandom rnd = new SplittableRandom(seed);
        for (int i = 0; i < generate; i++) {
            registryOrder.add(String.format(Locale.ROOT, "INC-H%06d", rnd.nextInt(1_000_000)));
        }

        BloomFilter bloom = BloomFilter.optimal(expected, p, seed);
        Set<String> exact = new HashSet<>();
        int duplicateEntries = 0;
        for (String id : registryOrder) {
            if (exact.add(id)) {
                bloom.add(id);
            } else {
                duplicateEntries++;
            }
        }

        List<ScreenedId> results = new ArrayList<>();
        int avoided = 0;
        for (String raw : new LinkedHashSet<>(incoming)) {
            String id = raw.trim();
            boolean maybe = bloom.mightContain(id);
            List<Long> bits = new ArrayList<>();
            for (long b : bloom.positionsOf(id)) {
                bits.add(b);
            }
            if (!maybe) {
                avoided++;
                results.add(new ScreenedId(id, "DEFINITELY_ABSENT", "NEW", false, bits));
            } else {
                boolean dup = exact.contains(id);
                results.add(new ScreenedId(id, "POSSIBLY_PRESENT", dup ? "CONFIRMED_DUPLICATE" : "FALSE_POSITIVE", true, bits));
            }
        }

        int fp = 0;
        int tested = 0;
        SplittableRandom probeRnd = new SplittableRandom(seed ^ 0x9E3779B97F4A7C15L);
        while (tested < probes) {
            String probe = "PROBE-" + Long.toHexString(probeRnd.nextLong());
            if (exact.contains(probe)) {
                continue; // must be known-absent
            }
            tested++;
            if (bloom.mightContain(probe)) {
                fp++;
            }
        }

        List<String> warnings = new ArrayList<>();
        if (bloom.insertedCount() > expected) {
            warnings.add("Registry has " + bloom.insertedCount() + " ids but the filter was sized for " + expected
                    + "; the real false-positive rate is above the target.");
        }
        long m = bloom.bitCount();
        int k = bloom.hashCount();
        return new ScreenResponse(m, k, (m + 7) / 8, expected, p, bloom.insertedCount(), bloom.setBitCount(),
                round(bloom.setBitCount() / (double) m), round(BloomFilter.falsePositiveProbability(m, k, expected)),
                round(bloom.theoreticalFalsePositiveProbability()), tested, fp, tested == 0 ? 0 : round(fp / (double) tested),
                exact.size(), duplicateEntries, registryOrder.stream().distinct().limit(10).toList(), results, avoided,
                List.of("m = ceil(-n ln p / (ln 2)^2)", "k = round((m / n) ln 2)", "FPR ~ (1 - e^(-k n / m))^k",
                        "positions g_i(x) = (h1(x) + i * h2(x)) mod m, i = 0..k-1"),
                warnings);
    }

    private static void checkIds(List<String> ids, String field) {
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (id == null || id.isBlank() || id.trim().length() > 64) {
                throw new ValidationException(field + "[" + i + "]", "ids must be 1-64 non-blank characters");
            }
        }
    }

    private static double round(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
