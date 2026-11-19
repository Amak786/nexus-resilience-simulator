package edu.nexus.algorithms;

import edu.nexus.algorithms.select.RandomizedQuickselect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickselectTest {

    @Test
    @DisplayName("QS-1: every k of a small array matches the sorted order")
    void everyRank() {
        double[] v = {7, 2, 9, 4, 4, 1, 8};
        double[] s = v.clone();
        Arrays.sort(s);
        for (int k = 0; k < v.length; k++) {
            assertEquals(s[k], RandomizedQuickselect.select(v, k, 1).value());
        }
    }

    @Test
    @DisplayName("QS-2: input array is not modified")
    void inputUntouched() {
        double[] v = {5, 3, 1, 4, 2};
        double[] copy = v.clone();
        RandomizedQuickselect.select(v, 2, 9);
        assertArrayEquals(copy, v);
    }

    @Test
    @DisplayName("QS-3 (property, seed 21): 500 random arrays with many duplicates match a full sort")
    void matchesSortWithDuplicates() {
        SplittableRandom rnd = new SplittableRandom(21);
        for (int trial = 0; trial < 500; trial++) {
            int n = 1 + rnd.nextInt(400);
            double[] v = new double[n];
            for (int i = 0; i < n; i++) {
                v[i] = rnd.nextInt(20);
            }
            double[] s = v.clone();
            Arrays.sort(s);
            for (double p : new double[] {0, 25, 50, 90, 95, 100}) {
                int rank = Math.max(1, (int) Math.ceil(p / 100 * n));
                assertEquals(s[rank - 1], RandomizedQuickselect.percentile(v, p, trial).value());
            }
        }
    }

    @Test
    @DisplayName("QS-4 (boundary): single element; all-equal array finishes in one linear pass")
    void boundaries() {
        assertEquals(3.5, RandomizedQuickselect.select(new double[] {3.5}, 0, 1).value());
        double[] same = new double[10_000];
        Arrays.fill(same, 7);
        RandomizedQuickselect.Selection s = RandomizedQuickselect.percentile(same, 90, 1);
        assertEquals(7, s.value());
        assertEquals(10_000, s.comparisons(), "3-way partition handles equal keys in one pass");
    }

    @Test
    @DisplayName("QS-5 (reproducibility): same seed gives the same comparison count; value never depends on seed")
    void seededDeterminism() {
        SplittableRandom rnd = new SplittableRandom(3);
        double[] v = new double[50_000];
        for (int i = 0; i < v.length; i++) {
            v[i] = rnd.nextDouble();
        }
        RandomizedQuickselect.Selection a = RandomizedQuickselect.percentile(v, 90, 77);
        RandomizedQuickselect.Selection b = RandomizedQuickselect.percentile(v, 90, 77);
        RandomizedQuickselect.Selection c = RandomizedQuickselect.percentile(v, 90, 78);
        assertEquals(a, b);
        assertEquals(a.value(), c.value());
        assertTrue(a.comparisons() < 10L * v.length, "expected linear work, got " + a.comparisons());
    }

    @Test
    @DisplayName("QS-6 (invalid input): empty list, bad k and bad percentile are rejected")
    void invalidInput() {
        assertThrows(IllegalArgumentException.class, () -> RandomizedQuickselect.select(new double[0], 0, 1));
        assertThrows(IllegalArgumentException.class, () -> RandomizedQuickselect.select(new double[] {1}, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> RandomizedQuickselect.percentile(new double[] {1}, 101, 1));
        assertThrows(IllegalArgumentException.class, () -> RandomizedQuickselect.percentile(new double[] {1}, -1, 1));
    }
}
