package edu.trace.model;

import java.util.List;

/** Input rejected; mapped to HTTP 400 with every issue listed. */
public class ValidationException extends RuntimeException {

    private final transient List<ValidationIssue> issues;

    public ValidationException(List<ValidationIssue> issues) {
        super(issues.isEmpty() ? "invalid input" : issues.get(0).field() + ": " + issues.get(0).message());
        this.issues = List.copyOf(issues);
    }

    public ValidationException(String field, String message) {
        this(List.of(new ValidationIssue(field, message)));
    }

    public List<ValidationIssue> getIssues() {
        return issues;
    }
}
