# Technology stack

| Technology | What it is | Role in TRACE | Alternative considered | Why chosen | Limitation | Viva answer |
| --- | --- | --- | --- | --- | --- | --- |
| Java 21 | Statically typed JVM language | All algorithms, rules, API | Python | Course requirement; predictable performance; records and switch expressions keep DTOs short | More verbose than Python | "The assessed algorithms are hand-written in Java, so performance and correctness are measurable." |
| Spring Boot 3.5 | Web framework | REST controllers, scheduling (`@Scheduled`), SSE (`SseEmitter`), JSON | Plain servlet / Javalin | Batteries included: validation errors, scheduling and SSE without extra libraries | Larger jar, slower start | "Spring gives me HTTP, scheduling and streaming so I could focus on the algorithms." |
| `java.net.http.HttpClient` | JDK HTTP client | Feed downloads and lookups with timeouts | OkHttp | No extra dependency; timeouts per request | Manual retry code | "Every outbound call has a connect and request timeout and at most two retries." |
| Jackson | JSON library | Parse feeds, serialise DTOs | Gson | Already part of Spring | - | - |
| Anthropic Java SDK | Official Claude client | Optional AI analyst tool loop | Raw HTTP | Official, typed, handles retries | Adds to jar size; needs a paid key | "The AI is optional and grounded; without a key the app still works." |
| springdoc-openapi | API docs | `/swagger-ui.html` | Hand-written docs | Docs generated from code | - | - |
| JUnit 5 + MockMvc | Testing | 100 unit and HTTP tests | - | Standard for Spring | - | "Tests compare each algorithm with an independent oracle." |
| React 18 + TypeScript | UI library + typed JS | Six screens | Plain JS, Angular | Types catch API mismatches; component reuse | Build step needed | "The UI only displays results; it never computes them." |
| Vite | Build tool | Dev server with `/api` proxy, production bundle | Webpack | Fast, simple config | - | - |
| Canvas 2D | Browser drawing API | Network graph | D3, Cytoscape | No dependency; enough for a few hundred nodes | Simple layout | "I wrote a small force-directed layout instead of adding a library." |
| PowerShell / bash sensors | OS scripts | Collect metadata, upload with token | Zeek, Suricata | Run on any Windows/Linux laptop with no install; Zeek/Suricata need a SPAN port and setup | Polling misses very short connections | "The sensor collects only metadata from a machine I am authorised to monitor." |
| Docker + Render | Container + free host | One-image deployment | VPS | Free, reproducible | Sleeps when idle; memory store resets on restart | - |
| abuse.ch Feodo, Spamhaus DROP, RIPEstat, AbuseIPDB | Public threat / network data | Evidence for findings and lookups | Paid feeds | Free, documented, attributable | Incomplete; lists can be outdated | "A list match is evidence, not proof." |
