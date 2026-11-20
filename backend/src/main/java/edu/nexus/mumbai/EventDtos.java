package edu.nexus.mumbai;

import java.util.List;

/** Types for OpenStreetMap map-note event records. */
public final class EventDtos {

    private EventDtos() {}

    /** One OSM note: a community-reported map issue (not a traffic incident). */
    public record NoteRecord(long id, double lat, double lon, String status, String created, String closed,
                             int comments, String text, String url) {}

    public record TreeMetrics(int size, int height, int blackHeight, boolean invariantsHold) {}

    /** {@code countInRange} uses subtree sizes (O(log n)); {@code items} is the ordered range scan. */
    public record EventListResponse(String from, String to, String status, int countInRange, int returned,
                                    List<NoteRecord> items, TreeMetrics index, String key) {}

    public record DayCount(String date, long count) {}

    public record MonthCount(String month, long count) {}

    public record Percentile(double percentile, double valueDays, long comparisons, boolean matchesSortOracle) {}

    /**
     * Segment-tree answers over notes-per-day for [from, to], cross-checked with a straight scan, and
     * Quickselect percentiles of resolution time (days) for closed notes created in the range.
     */
    public record EventStatsResponse(String from, String to, int days, long totalNotes, DayCount busiestDay,
                                     DayCount quietestDay, boolean matchesScan, long segmentTreeNodesVisited,
                                     long scanCellsTouched, List<MonthCount> monthly, int resolvedSample,
                                     boolean sampleAdequate, List<Percentile> resolutionPercentiles,
                                     List<String> notes) {}

    public record RefreshResponse(String status, String fetchedAt, int fetched, int possiblyPresent,
                                  int confirmedDuplicates, int bloomFalsePositives, int newRecords, int statusUpdates,
                                  long bloomBits, int bloomHashes, double bloomTheoreticalFpr, String message) {}
}
