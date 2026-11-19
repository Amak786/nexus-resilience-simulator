package edu.nexus.model;

/**
 * A road segment. {@code capacity} is throughput (relief units per planning window, per direction) and
 * is used only by the flow network. {@code lengthKm} is travel distance and is used only by route
 * planning. The two are never mixed. {@code oneWay} roads allow flow only from {@code from} to {@code to}.
 */
public record Road(String id, String from, String to, Long capacity, Double lengthKm, Boolean oneWay) {

    public boolean oneWayOnly() {
        return Boolean.TRUE.equals(oneWay);
    }
}
