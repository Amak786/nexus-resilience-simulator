package edu.nexus.algorithms;

import edu.nexus.algorithms.bloom.BloomFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BloomFilterTest {

    @Test
    @DisplayName("BF-1: no false negatives for 10,000 inserted ids (insertion-only model)")
    void noFalseNegatives() {
        BloomFilter bf = BloomFilter.optimal(10_000, 0.01, 1);
        for (int i = 0; i < 10_000; i++) {
            bf.add("INC-" + i);
        }
        for (int i = 0; i < 10_000; i++) {
            assertTrue(bf.mightContain("INC-" + i));
        }
    }

    @Test
    @DisplayName("BF-2 (boundary): an empty filter answers 'definitely absent' for everything")
    void emptyFilter() {
        BloomFilter bf = BloomFilter.optimal(100, 0.01, 1);
        for (int i = 0; i < 1000; i++) {
            assertFalse(bf.mightContain("x" + i));
        }
        assertEquals(0.0, bf.theoreticalFalsePositiveProbability());
    }

    @Test
    @DisplayName("BF-3: sizing formulas m = ceil(-n ln p / ln^2 2) and k = round(m/n ln 2)")
    void sizingFormulas() {
        assertEquals(9586, BloomFilter.optimalBits(1000, 0.01));
        assertEquals(7, BloomFilter.optimalHashes(9586, 1000));
        BloomFilter bf = BloomFilter.optimal(1000, 0.01, 1);
        assertEquals(9586, bf.bitCount());
        assertEquals(7, bf.hashCount());
    }

    @Test
    @DisplayName("BF-4 (probabilistic, seed 7): measured FPR on 200k known-absent ids is within 0.5x-2x of theory")
    void falsePositiveRate() {
        BloomFilter bf = BloomFilter.optimal(20_000, 0.01, 42);
        for (int i = 0; i < 20_000; i++) {
            bf.add("in-" + i);
        }
        SplittableRandom rnd = new SplittableRandom(7);
        int fp = 0;
        int probes = 200_000;
        for (int i = 0; i < probes; i++) {
            if (bf.mightContain("out-" + rnd.nextLong())) {
                fp++;
            }
        }
        double observed = fp / (double) probes;
        double theory = bf.theoreticalFalsePositiveProbability();
        assertTrue(observed < 2 * theory && observed > theory / 2, "observed " + observed + " theory " + theory);
    }

    @Test
    @DisplayName("BF-5: a false positive exists and is caught only by exact verification (tiny filter)")
    void falsePositiveNeedsVerification() {
        BloomFilter bf = new BloomFilter(64, 2, 3);
        java.util.Set<String> exact = new java.util.HashSet<>();
        for (int i = 0; i < 40; i++) {
            bf.add("REG-" + i);
            exact.add("REG-" + i);
        }
        String falsePositive = null;
        for (int i = 0; i < 10_000 && falsePositive == null; i++) {
            String probe = "NEW-" + i;
            if (bf.mightContain(probe) && !exact.contains(probe)) {
                falsePositive = probe;
            }
        }
        assertTrue(falsePositive != null, "an overloaded 64-bit filter must produce a false positive");
        assertFalse(exact.contains(falsePositive), "exact store correctly says the id is new");
    }

    @Test
    @DisplayName("BF-6: hashing is deterministic for a seed and changes with the seed")
    void deterministicHashing() {
        BloomFilter a = new BloomFilter(1024, 4, 99);
        BloomFilter b = new BloomFilter(1024, 4, 99);
        BloomFilter c = new BloomFilter(1024, 4, 100);
        assertArrayEquals(a.positionsOf("INC-1001"), b.positionsOf("INC-1001"));
        assertFalse(java.util.Arrays.equals(a.positionsOf("INC-1001"), c.positionsOf("INC-1001")));
    }

    @Test
    @DisplayName("BF-7 (invalid input): bad n, p, sizes and null items are rejected")
    void invalidInput() {
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.optimalBits(0, 0.01));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.optimalBits(10, 0));
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.optimalBits(10, 1));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(4, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BloomFilter(64, 0, 0));
        BloomFilter bf = new BloomFilter(64, 2, 0);
        assertThrows(IllegalArgumentException.class, () -> bf.add(null));
    }
}
