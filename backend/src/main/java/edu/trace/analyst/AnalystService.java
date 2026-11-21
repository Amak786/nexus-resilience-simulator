package edu.trace.analyst;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.trace.model.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Grounded AI analyst: Claude answers questions about the network, but every fact must come from {@link AnalystTools},
 * which run the application's own algorithms on the real data. The API key stays on the server (ANTHROPIC_API_KEY).
 * Without a key the endpoint still works: it returns the computed evidence and says that no AI interpretation was made.
 */
@Service
public class AnalystService {

    private static final Logger log = LoggerFactory.getLogger(AnalystService.class);
    public static final int MAX_QUESTION_CHARS = 600;
    static final int MAX_TOOL_ROUNDS = 6;

    static final String SYSTEM = """
            You are the investigation assistant inside TRACE, a student network-security tool. Sensors upload connection metadata             (addresses, ports, protocol, process names); the server matches public addresses against downloaded threat lists             (abuse.ch Feodo Tracker, Spamhaus DROP) and optional AbuseIPDB, builds a graph of observed relationships and applies             documented rules. Answer only from the results of the tools you call in this conversation.

            Rules:
            - Every address, count, score, time, list name or algorithm result you mention must appear in a tool result. If no tool             provides it, say it is not available.
            - A list match or reputation score is evidence, not proof. Never say a host is compromised, infected or breached, and never             invent an incident. Only a person can mark a finding CONFIRMED_INCIDENT; report that status only if a tool shows it.
            - An IP address does not identify a person. Do not use country or location as a reason for suspicion.
            - Keep severity (impact if true) and confidence (strength of evidence) separate, as the tools do.
            - Call get_source_status when freshness or sensor connectivity matters, and say whether data is live or historical.
            - Structure the answer under three short headings: Observed data, Algorithm and rule results, Interpretation. Put your own             reasoning only under Interpretation, keep it cautious, and end with "Next steps:" (read-only investigation steps) and             "Uncertain:" (what the data cannot show).
            - Be concise: at most about 220 words. Plain text, no tables.
            """;


    public record EvidenceItem(String tool, JsonNode input, JsonNode result) {}

    public record AnalystResponse(boolean aiAvailable, String model, String answer, String answerKind, List<EvidenceItem> evidence,
                                  String stopReason, String generatedAt, String notice) {}

    private final AnalystTools tools;
    private final ObjectMapper mapper;
    private final AnthropicClient client;
    private final String model;
    private final int perClientLimit;
    private final int dailyLimit;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicInteger today = new AtomicInteger();
    private volatile long dayStart = System.currentTimeMillis();

    private record Window(long start, AtomicInteger count) {}

    public AnalystService(AnalystTools tools, ObjectMapper mapper,
                          @Value("${ANTHROPIC_API_KEY:}") String apiKey,
                          @Value("${trace.ai.model:claude-opus-5-5}") String model,
                          @Value("${trace.ai.per-client-per-10min:8}") int perClientLimit,
                          @Value("${trace.ai.daily-limit:300}") int dailyLimit) {
        this.tools = tools;
        this.mapper = mapper;
        this.model = model;
        this.perClientLimit = perClientLimit;
        this.dailyLimit = dailyLimit;
        this.client = apiKey == null || apiKey.isBlank() ? null
                : AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofSeconds(60)).maxRetries(2).build();
    }

    public boolean aiConfigured() {
        return client != null;
    }

    public String model() {
        return model;
    }

    public AnalystResponse ask(String question, String clientId) {
        String q = question == null ? "" : question.strip();
        if (q.isEmpty() || q.length() > MAX_QUESTION_CHARS) {
            throw new ValidationException("question", "ask a question of 1-" + MAX_QUESTION_CHARS + " characters");
        }
        rateLimit(clientId == null ? "unknown" : clientId);
        if (client == null) {
            return evidenceOnly(q, "AI analyst not configured on this server (no ANTHROPIC_API_KEY). Showing the computed evidence "
                    + "for your question without an AI interpretation.");
        }
        List<EvidenceItem> evidence = new ArrayList<>();
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(4000L)
                .system(SYSTEM)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage(q);
        for (AnalystTools.ToolSpec spec : AnalystTools.SPECS) {
            b.addTool(toTool(spec));
        }
        try {
            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                Message m = client.messages().create(b.build());
                b.addMessage(m);
                StopReason stop = m.stopReason().orElse(null);
                if (StopReason.REFUSAL.equals(stop)) {
                    return new AnalystResponse(true, model, "The model declined to answer this question.", "REFUSAL", evidence,
                            "refusal", Instant.now().toString(), null);
                }
                if (!StopReason.TOOL_USE.equals(stop)) {
                    String text = text(m);
                    return new AnalystResponse(true, model, text.isBlank() ? "(no answer text)" : text, "AI_INTERPRETATION",
                            evidence, stop == null ? null : stop.toString(), Instant.now().toString(),
                            StopReason.MAX_TOKENS.equals(stop) ? "The answer was cut off at the length limit." : null);
                }
                List<ContentBlockParam> results = new ArrayList<>();
                for (ContentBlock block : m.content()) {
                    if (block.toolUse().isEmpty()) {
                        continue;
                    }
                    ToolUseBlock use = block.toolUse().get();
                    JsonNode input = mapper.readTree(mapper.writeValueAsString(use._input()));
                    ObjectNode result = tools.execute(use.name(), input);
                    evidence.add(new EvidenceItem(use.name(), input, result));
                    results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(use.id())
                            .content(mapper.writeValueAsString(result))
                            .isError(result.has("error"))
                            .build()));
                }
                b.addMessage(MessageParam.ofUserOfContentBlockParams(results));
            }
            return new AnalystResponse(true, model, "Stopped after " + MAX_TOOL_ROUNDS + " tool rounds without a final answer.",
                    "INCOMPLETE", evidence, "max_rounds", Instant.now().toString(), null);
        } catch (RateLimitException e) {
            log.warn("Claude API rate limited");
            return fallback(q, evidence, "The AI provider is rate-limiting requests; showing computed evidence only.");
        } catch (AnthropicServiceException e) {
            log.warn("Claude API error {}", e.statusCode());
            return fallback(q, evidence, "The AI provider returned an error (HTTP " + e.statusCode() + "); showing computed evidence only.");
        } catch (Exception e) {
            log.warn("AI analyst failed: {}", e.toString());
            return fallback(q, evidence, "The AI provider could not be reached; showing computed evidence only.");
        }
    }

    private AnalystResponse fallback(String q, List<EvidenceItem> partial, String notice) {
        AnalystResponse r = evidenceOnly(q, notice);
        List<EvidenceItem> all = new ArrayList<>(partial);
        all.addAll(r.evidence());
        return new AnalystResponse(true, model, r.answer(), r.answerKind(), all, r.stopReason(), r.generatedAt(), notice);
    }

    /** Deterministic, non-AI answer: picks the tools relevant to the question by keyword and returns their results. */
    AnalystResponse evidenceOnly(String q, String notice) {
        String l = q.toLowerCase();
        List<EvidenceItem> ev = new ArrayList<>();
        ev.add(run("get_source_status", mapper.createObjectNode()));
        java.util.regex.Matcher ip = java.util.regex.Pattern.compile("\\b(\\d{1,3}(?:\\.\\d{1,3}){3})\\b").matcher(q);
        if (ip.find()) {
            ev.add(run("investigate_ip", mapper.createObjectNode().put("ip", ip.group(1))));
        }
        if (l.contains("finding") || l.contains("suspicious") || l.contains("alert") || l.contains("attention") || l.contains("why")) {
            ev.add(run("list_findings", mapper.createObjectNode().put("limit", 5)));
        }
        if (l.contains("graph") || l.contains("network") || l.contains("central") || l.contains("connect") || l.contains("change")) {
            ev.add(run("network_summary", mapper.createObjectNode()));
        }
        for (String algo : List.of("dijkstra", "bloom", "dsu", "flow", "quickselect", "red-black", "segment", "centrality", "bfs", "dfs", "a*", "tsp")) {
            if (l.contains(algo)) {
                ev.add(run("explain_algorithm", mapper.createObjectNode().put("name", algo)));
                break;
            }
        }
        if (ev.size() == 1) {
            ev.add(run("list_findings", mapper.createObjectNode().put("limit", 5)));
        }
        return new AnalystResponse(client != null, model, "No AI interpretation was generated. The evidence below is the direct "
                + "output of TRACE's rules, algorithms and data sources (deterministic).", "EVIDENCE_ONLY", ev, null, Instant.now().toString(), notice);
    }

    private EvidenceItem run(String tool, ObjectNode input) {
        return new EvidenceItem(tool, input, tools.execute(tool, input));
    }

    private static String text(Message m) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : m.content()) {
            block.text().ifPresent(t -> sb.append(t.text()));
        }
        return sb.toString().strip();
    }

    private static Tool toTool(AnalystTools.ToolSpec spec) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        spec.properties().forEach((k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)));
        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(spec.required()).build())
                .build();
    }

    /** Cost control: per-client window of 10 minutes and a global daily cap. */
    void rateLimit(String clientId) {
        long now = System.currentTimeMillis();
        if (now - dayStart > Duration.ofDays(1).toMillis()) {
            dayStart = now;
            today.set(0);
        }
        Window w = windows.compute(clientId, (k, old) -> old == null || now - old.start() > Duration.ofMinutes(10).toMillis()
                ? new Window(now, new AtomicInteger()) : old);
        if (w.count().incrementAndGet() > perClientLimit || today.incrementAndGet() > dailyLimit) {
            throw new RateLimitedException();
        }
    }

    /** Raised when a client asks too often (HTTP 429). */
    public static final class RateLimitedException extends RuntimeException {
        RateLimitedException() {
            super("Too many analyst questions. Please wait a few minutes.");
        }
    }
}
