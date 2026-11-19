package edu.nexus.model;

/**
 * A reported infrastructure incident (synthetic). {@code zoneId} optionally links it to a demand zone;
 * {@code reportedHour} is the hour in the planning timeline when it was reported.
 */
public record Incident(String id, String title, String zoneId, Integer severity, Long affectedPeople,
                       Integer reportedHour) {}
