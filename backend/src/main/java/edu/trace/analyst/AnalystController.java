package edu.trace.analyst;

import edu.trace.net.TraceController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Grounded AI analyst (viewer access: it reads observed data). */
@Tag(name = "AI analyst")
@RestController
public class AnalystController {

    private final AnalystService analyst;
    private final TraceController auth;

    public AnalystController(AnalystService analyst, TraceController auth) {
        this.analyst = analyst;
        this.auth = auth;
    }

    public record AskRequest(String question) {}

    @GetMapping("/api/analyst/info")
    public Map<String, Object> info() {
        return Map.of("aiConfigured", analyst.aiConfigured(), "model", analyst.model(),
                "tools", AnalystTools.SPECS.stream().map(AnalystTools.ToolSpec::name).toList());
    }

    @Operation(summary = "Ask a question; the answer is grounded in tool results computed by TRACE")
    @PostMapping("/api/analyst/ask")
    public AnalystService.AnalystResponse ask(@RequestBody AskRequest req, HttpServletRequest http) {
        auth.requireViewer(http);
        String fwd = http.getHeader("X-Forwarded-For");
        String id = fwd != null && !fwd.isBlank() ? fwd.split(",")[0].strip() : http.getRemoteAddr();
        return analyst.ask(req == null ? null : req.question(), id);
    }

    @ExceptionHandler(AnalystService.RateLimitedException.class)
    public ResponseEntity<Map<String, Object>> tooMany(AnalystService.RateLimitedException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "RATE_LIMITED", "message", e.getMessage(), "issues", List.of()));
    }

    @ExceptionHandler(TraceController.AuthException.class)
    public ResponseEntity<Map<String, Object>> auth(TraceController.AuthException e) {
        return auth.auth(e);
    }
}
