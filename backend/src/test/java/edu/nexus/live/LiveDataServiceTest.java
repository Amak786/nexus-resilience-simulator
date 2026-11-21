package edu.nexus.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.nexus.mumbai.EventService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ingestion layer with a fake HTTP transport: parsing, validation, retries, timeouts and freshness (no network). */
class LiveDataServiceTest {

    static final ObjectMapper M = new ObjectMapper();
    static EventService events;

    static final String WEATHER = """
            {"latitude":19.086,"longitude":72.853,"utc_offset_seconds":19800,
             "current":{"time":"2026-11-21T19:00","temperature_2m":26.9,"relative_humidity_2m":61,"precipitation":0.0,
                        "rain":0.0,"wind_speed_10m":8.3,"weather_code":0},
             "hourly":{"time":["2026-11-21T18:00","2026-11-21T19:00","2026-11-21T20:00","2026-11-22T02:00"],
                       "precipitation":[5.0,0.4,0.6,9.9]}}""";
    static final String EDITS = """
            {"osm3s":{"timestamp_osm_base":"2026-11-21T13:36:04Z"},"elements":[
             {"type":"way","id":11,"timestamp":"2026-11-21T08:00:00Z","version":4,"changeset":99,"user":"someone","uid":7,
              "tags":{"highway":"primary","name":"Tilak Road"}},
             {"type":"way","id":12,"timestamp":"2026-11-21T10:00:00Z","version":2,"changeset":100,"tags":{"highway":"residential"}},
             {"type":"way","id":13,"timestamp":"2026-11-21T12:00:00Z","version":1,"changeset":101,"tags":{"highway":"trunk","ref":"NH48"}}]}""";

    @BeforeAll
    static void init() throws Exception {
        events = new EventService(M);
    }

    /** A clock that the test can move forward. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-21T14:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static LiveDataService service(Deque<Object> script, AtomicInteger calls, MutableClock clock) {
        LiveDataService.Http http = req -> {
            calls.incrementAndGet();
            Object next = script.isEmpty() ? new LiveDataService.Response(500, "") : script.poll();
            if (next instanceof HttpTimeoutException t) {
                throw t;
            }
            return (LiveDataService.Response) next;
        };
        return new LiveDataService(M, events, http, clock, "2026-11-20T16:43:50Z", true) {
            @Override
            long sleepMillis(int attempt) {
                return 0; // no real waiting in tests
            }
        };
    }

    static LiveDataService.SourceStatus src(LiveDataService s, String id) {
        return s.status().sources().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("LV-1 (weather parse): observation time is converted to UTC, ingestion time is separate, next-6h rain summed")
    void weatherParsed() {
        MutableClock clock = new MutableClock();
        Deque<Object> script = new ArrayDeque<>();
        script.add(new LiveDataService.Response(200, WEATHER));
        LiveDataService s = service(script, new AtomicInteger(), clock);
        s.refreshWeather();
        var w = s.weather();
        assertEquals("2026-11-21T13:30:00Z", w.observedAtUtc(), "19:00 IST = 13:30 UTC");
        assertEquals(1.0, w.next6hPrecipitationMm(), 1e-9, "only 19:00 and 20:00 fall in the next 6 hours");
        assertEquals("clear sky", w.description());
        var st = src(s, "weather");
        assertEquals("OK", st.status());
        assertEquals("2026-11-21T13:30:00Z", st.observedAt());
        assertEquals("2026-11-21T14:00:00Z", st.lastSuccessAt());
        assertEquals(30L, st.ageMinutes());
    }

    @Test
    @DisplayName("LV-2 (validation): a response for the wrong place or with impossible values is rejected, not stored")
    void weatherValidation() throws Exception {
        LiveDataService s = service(new ArrayDeque<>(), new AtomicInteger(), new MutableClock());
        assertThrows(IllegalArgumentException.class, () -> s.parseWeather(M.readTree(WEATHER.replace("19.086", "28.6"))));
        assertThrows(IllegalArgumentException.class, () -> s.parseWeather(M.readTree(WEATHER.replace("26.9", "99.0"))));
        assertThrows(IllegalArgumentException.class, () -> s.parseWeather(M.readTree("{\"latitude\":19.08,\"longitude\":72.85}")));
    }

    @Test
    @DisplayName("LV-3 (OSM edits): only arterial ways are kept, newest first, and user names / ids are dropped")
    void editsParsed() throws Exception {
        LiveDataService s = service(new ArrayDeque<>(), new AtomicInteger(), new MutableClock());
        var edits = s.parseEdits(M.readTree(EDITS));
        assertEquals(2, edits.size(), "the residential way is outside the graph's road classes");
        assertEquals(13, edits.get(0).wayId());
        assertEquals("NH48", edits.get(0).name(), "ref is used when there is no name");
        assertFalse(M.writeValueAsString(edits).contains("someone"), "no personal data is stored");
        assertThrows(IllegalArgumentException.class, () -> s.parseEdits(M.readTree("{\"remark\":\"runtime error\"}")));
    }

    @Test
    @DisplayName("LV-4 (retries): 504 then 200 succeeds after one retry; 400 fails at once; three timeouts give an ERROR status")
    void retriesAndTimeouts() {
        MutableClock clock = new MutableClock();
        AtomicInteger calls = new AtomicInteger();
        Deque<Object> script = new ArrayDeque<>();
        script.add(new LiveDataService.Response(504, "busy"));
        script.add(new LiveDataService.Response(200, EDITS));
        LiveDataService s = service(script, calls, clock);
        s.refreshRoadEdits();
        assertEquals(2, calls.get());
        assertEquals("OK", src(s, "osm-edits").status());
        assertEquals(2, src(s, "osm-edits").records());

        AtomicInteger calls2 = new AtomicInteger();
        Deque<Object> bad = new ArrayDeque<>();
        bad.add(new LiveDataService.Response(400, "bad query"));
        LiveDataService s2 = service(bad, calls2, clock);
        s2.refreshRoadEdits();
        assertEquals(1, calls2.get(), "client errors are not retried");
        assertEquals("ERROR", src(s2, "osm-edits").status());
        assertTrue(src(s2, "osm-edits").error().contains("HTTP 400"));

        AtomicInteger calls3 = new AtomicInteger();
        Deque<Object> slow = new ArrayDeque<>();
        for (int i = 0; i < 3; i++) {
            slow.add(new HttpTimeoutException("timed out"));
        }
        LiveDataService s3 = service(slow, calls3, clock);
        s3.refreshWeather();
        assertEquals(3, calls3.get(), "one try plus two retries");
        assertEquals("ERROR", src(s3, "weather").status());
        assertNull(s3.weather(), "nothing is fabricated when the source fails");
    }

    @Test
    @DisplayName("LV-5 (freshness): a failed refresh keeps the last good value but marks it STALE; old data becomes STALE")
    void staleness() {
        MutableClock clock = new MutableClock();
        Deque<Object> script = new ArrayDeque<>();
        script.add(new LiveDataService.Response(200, WEATHER));
        LiveDataService s = service(script, new AtomicInteger(), clock);
        assertEquals("NOT_YET_FETCHED", src(s, "weather").status());
        s.refreshWeather();
        assertEquals("OK", src(s, "weather").status());
        clock.now = clock.now.plus(Duration.ofMinutes(20));
        s.refreshWeather(); // script empty -> HTTP 500 x3
        assertEquals("STALE", src(s, "weather").status());
        assertNotNull(s.weather(), "last validated value is kept");
        LiveDataService fresh = service(new ArrayDeque<>(java.util.List.of(new LiveDataService.Response(200, WEATHER))),
                new AtomicInteger(), clock);
        fresh.refreshWeather();
        clock.now = clock.now.plus(Duration.ofMinutes(50));
        assertEquals("STALE", src(fresh, "weather").status(), "older than 3 x 15 minutes");
        assertEquals("NOT_AVAILABLE", src(fresh, "traffic").status(), "traffic is never faked");
    }
}
