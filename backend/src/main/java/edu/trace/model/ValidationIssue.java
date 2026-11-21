package edu.trace.model;

/** One input problem, naming the exact field path. */
public record ValidationIssue(String field, String message) {}
