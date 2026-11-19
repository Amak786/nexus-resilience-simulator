package edu.nexus.model;

/** Role of a city node in the model. */
public enum NodeType {
    /** Relief depot: holds {@code supply} units of relief kits. */
    DEPOT,
    /** Road junction / staging point: transit only. */
    JUNCTION,
    /** Ward-level demand zone (a shelter cluster): needs {@code demand} units. */
    DEMAND_ZONE
}
