package edu.nexus.model;

import java.util.List;

/** Hourly city-wide relief demand forecast (units per hour), 24 to 168 hours long. */
public record DemandTimeline(String label, List<Long> hourlyDemand) {}
