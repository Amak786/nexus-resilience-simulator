package edu.nexus.model;

/**
 * A node of the synthetic city. Coordinates are kilometres on a city grid (used only for drawing).
 * {@code supply} applies to depots; {@code demand}, {@code severity} (1-5), {@code vulnerablePercent}
 * (0-100) and {@code population} apply to demand zones.
 */
public record CityNode(String id, String name, NodeType type, Double x, Double y, Long supply, Long demand,
                       Integer severity, Integer vulnerablePercent, Long population) {}
