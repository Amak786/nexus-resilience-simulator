package edu.nexus.algorithms.rbtree;

import java.util.Objects;

/**
 * Ordering key for the emergency priority index.
 *
 * <p>Higher {@code score} sorts first; equal scores are broken by {@code zoneId} in ascending
 * lexicographic order. Because zone ids are unique, no two keys compare equal, which the red-black
 * tree requires (it stores a set of distinct keys).
 */
public record PriorityKey(int score, String zoneId) implements Comparable<PriorityKey> {

    public PriorityKey {
        Objects.requireNonNull(zoneId, "zoneId");
    }

    @Override
    public int compareTo(PriorityKey o) {
        int byScore = Integer.compare(o.score, score); // descending
        return byScore != 0 ? byScore : zoneId.compareTo(o.zoneId);
    }

    @Override
    public String toString() {
        return score + "/" + zoneId;
    }
}
