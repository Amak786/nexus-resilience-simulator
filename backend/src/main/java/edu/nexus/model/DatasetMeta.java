package edu.nexus.model;

/** Descriptive metadata; {@code synthetic} must be true for the bundled demo data. */
public record DatasetMeta(String name, String description, Boolean synthetic, String units, String version) {}
