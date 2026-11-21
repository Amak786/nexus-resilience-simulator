package edu.trace.algorithms.flow;

/**
 * Controls how much of a solver's execution is recorded.
 *
 * @param maxSteps      maximum number of augmenting paths to record (0 = record none)
 * @param edgeSnapshots record the flow on every edge after each recorded step (for visual replay)
 */
public record TraceOptions(int maxSteps, boolean edgeSnapshots) {

    public static final TraceOptions NONE = new TraceOptions(0, false);

    public TraceOptions {
        if (maxSteps < 0) {
            throw new IllegalArgumentException("maxSteps must be non-negative");
        }
    }
}
