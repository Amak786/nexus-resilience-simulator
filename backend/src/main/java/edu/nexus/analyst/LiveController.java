package edu.nexus.analyst;

import edu.nexus.live.LiveDataService;
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

import java.util.Map;

/** Live data status and the grounded AI analyst. */
@RestController
public class LiveController {

    private final LiveDataService live;
    private final AnalystService analyst;

    public LiveController(LiveDataService live, AnalystService analyst) {
        this.live = live;
        this.analyst = analyst;
    }

    public record AskRequest(String question) {}

    @Tag(name = "Live data")
    @Operation(summary = "Freshness of every source, latest weather and OSM road edits since the snapshot (cached; no provider call)")
    @GetMapping("/api/live/status")
    public LiveDataService.LiveStatus status() {
        return live.status();
    }

    @Tag(name = "AI analyst")
    @Operation(summary = "Whether the AI analyst is configured on this server")
    @GetMapping("/api/analyst/info")
    public Map<String, Object> info() {
        return Map.of("aiConfigured", analyst.aiConfigured(), "model", analyst.model(), "tools",
                AnalystTools.SPECS.stream().map(AnalystTools.ToolSpec::name).toList());
    }

    @Tag(name = "AI analyst")
    @Operation(summary = "Ask a question; the answer is grounded in tool results computed by the application")
    @PostMapping("/api/analyst/ask")
    public AnalystService.AnalystResponse ask(@RequestBody AskRequest req, HttpServletRequest http) {
        String fwd = http.getHeader("X-Forwarded-For");
        String id = fwd != null && !fwd.isBlank() ? fwd.split(",")[0].strip() : http.getRemoteAddr();
        return analyst.ask(req == null ? null : req.question(), id);
    }

    @ExceptionHandler(AnalystService.RateLimitedException.class)
    public ResponseEntity<Map<String, Object>> tooMany(AnalystService.RateLimitedException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "RATE_LIMITED", "message", e.getMessage(), "issues", java.util.List.of()));
    }
}
