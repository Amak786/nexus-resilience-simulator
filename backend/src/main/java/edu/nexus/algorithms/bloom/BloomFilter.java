package edu.nexus.algorithms.bloom;

import java.nio.charset.StandardCharsets;

/**
 * Bloom filter over strings: a bit array of m bits probed at k positions per item.
 *
 * <pre>
 * ADD(x):          for i in 0..k-1: set bit g_i(x)
 * MIGHT-CONTAIN(x): return all bits g_i(x) are set
 * g_i(x) = (h1(x) + i * h2(x)) mod m        (Kirsch-Mitzenmacher double hashing)
 * </pre>
 *
 * <p><b>Answers.</b> {@code false} = definitely absent. {@code true} = possibly present: the bits may
 * have been set by other items (a false positive), so callers must confirm with an exact store.
 *
 * <p><b>False negatives.</b> Under the insertion-only model used here (no deletes, no bit resets, the
 * same hash family and seed for add and query) a false negative is impossible: an added item's k bits
 * are set and are never cleared. A false negative could only arise outside that model, e.g. if a
 * "delete" cleared shared bits, the filter were rebuilt with a different seed, or memory were corrupted.
 *
 * <p><b>Sizing.</b> For n expected items and target false-positive probability p:
 * m = ceil(-n ln p / (ln 2)^2), k = round((m / n) ln 2), and after n insertions the false-positive
 * probability is approximately (1 - e^(-k n / m))^k.
 *
 * <p><b>Complexity.</b> add and query O(k); space m bits, independent of the length of the items.
 * Hashing is deterministic: a seeded 64-bit FNV-1a pass followed by the SplitMix64 finalizer.
 */
public final class BloomFilter {

    public static final long DEFAULT_SEED = 0x5EED_C1F1_0000_2026L;
    public static final long MAX_BITS = 1L << 30;
    public static final int MAX_HASHES = 30;

    private final long[] words;
    private final long bits;
    private final int hashes;
    private final long seed;
    private long inserted;
    private long setBits;

    public BloomFilter(long bits, int hashes, long seed) {
        if (bits < 8 || bits > MAX_BITS) {
            throw new IllegalArgumentException("bit count must be in [8, " + MAX_BITS + "]");
        }
        if (hashes < 1 || hashes > MAX_HASHES) {
            throw new IllegalArgumentException("hash count must be in [1, " + MAX_HASHES + "]");
        }
        this.bits = bits;
        this.hashes = hashes;
        this.seed = seed;
        this.words = new long[(int) ((bits + 63) / 64)];
    }

    /** Filter sized with the optimal formulas for {@code expectedInsertions} and {@code falsePositiveRate}. */
    public static BloomFilter optimal(long expectedInsertions, double falsePositiveRate, long seed) {
        long m = optimalBits(expectedInsertions, falsePositiveRate);
        return new BloomFilter(m, optimalHashes(m, expectedInsertions), seed);
    }

    /** m = ceil(-n ln p / (ln 2)^2), at least 8. */
    public static long optimalBits(long n, double p) {
        if (n <= 0) {
            throw new IllegalArgumentException("expected insertions must be positive");
        }
        if (!(p > 0.0 && p < 1.0)) {
            throw new IllegalArgumentException("false-positive rate must be in (0, 1)");
        }
        double ln2 = Math.log(2.0);
        double m = Math.ceil(-n * Math.log(p) / (ln2 * ln2));
        if (m > MAX_BITS) {
            throw new IllegalArgumentException("filter would need " + (long) m + " bits; limit is " + MAX_BITS);
        }
        return Math.max(8, (long) m);
    }

    /** k = round((m / n) ln 2), clamped to [1, MAX_HASHES]. */
    public static int optimalHashes(long m, long n) {
        long k = Math.round((double) m / n * Math.log(2.0));
        return (int) Math.max(1, Math.min(MAX_HASHES, k));
    }

    /** Approximate false-positive probability (1 - e^(-k n / m))^k after n insertions. */
    public static double falsePositiveProbability(long m, int k, long n) {
        return Math.pow(1.0 - Math.exp(-(double) k * n / m), k);
    }

    public void add(String item) {
        long[] h = baseHashes(item);
        for (int i = 0; i < hashes; i++) {
            long idx = position(h[0], h[1], i);
            int w = (int) (idx >>> 6);
            long mask = 1L << (idx & 63);
            if ((words[w] & mask) == 0) {
                words[w] |= mask;
                setBits++;
            }
        }
        inserted++;
    }

    /** {@code true} = possibly present (must be verified); {@code false} = definitely absent. */
    public boolean mightContain(String item) {
        long[] h = baseHashes(item);
        for (int i = 0; i < hashes; i++) {
            long idx = position(h[0], h[1], i);
            if ((words[(int) (idx >>> 6)] & (1L << (idx & 63))) == 0) {
                return false;
            }
        }
        return true;
    }

    /** The k bit positions probed for an item (for the inspector UI). */
    public long[] positionsOf(String item) {
        long[] h = baseHashes(item);
        long[] p = new long[hashes];
        for (int i = 0; i < hashes; i++) {
            p[i] = position(h[0], h[1], i);
        }
        return p;
    }

    public long bitCount() {
        return bits;
    }

    public int hashCount() {
        return hashes;
    }

    public long insertedCount() {
        return inserted;
    }

    public long setBitCount() {
        return setBits;
    }

    public double theoreticalFalsePositiveProbability() {
        return falsePositiveProbability(bits, hashes, inserted);
    }

    private long position(long h1, long h2, int i) {
        return Long.remainderUnsigned(h1 + i * h2, bits);
    }

    private long[] baseHashes(String item) {
        if (item == null) {
            throw new IllegalArgumentException("item must not be null");
        }
        byte[] data = item.getBytes(StandardCharsets.UTF_8);
        long h = seed ^ (data.length * 0xC2B2AE3D27D4EB4FL);
        for (byte b : data) {
            h ^= (b & 0xFF);
            h *= 0x100000001B3L; // FNV-1a 64-bit prime
        }
        long h1 = mix64(h);
        long h2 = mix64(h1 ^ 0xD6E8FEB86659FD93L) | 1L; // odd step
        return new long[] {h1, h2};
    }

    /** SplitMix64 finalizer. */
    static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
