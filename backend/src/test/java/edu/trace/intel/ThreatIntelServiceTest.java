package edu.trace.intel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Feed parsing, Bloom + exact verification, red-black range lookups, failures, SSRF guard (fake HTTP, no network). */
class ThreatIntelServiceTest {

    static final ObjectMapper M = new ObjectMapper();

    @Test
    @DisplayName("TI-1 (Feodo): malformed rows are skipped; a listed IP matches; Bloom hits are always confirmed exactly")
    void feodo() {
        ThreatIntelService s = ThreatIntelServiceTestSupport.loaded(M);
        assertEquals(2, s.feodoEntries().size());
        var m = s.matches("50.16.16.211");
        assertEquals(1, m.size());
        assertTrue(m.get(0).detail().contains("QakBot"));
        assertTrue(s.matches("8.8.8.8").isEmpty());
        for (int i = 0; i < 2000; i++) {
            s.matches("9." + (i / 250) + "." + (i % 250) + ".1");
        }
        var b = s.bloomStats();
        assertEquals(b.possiblyPresent(), b.confirmed() + b.falsePositives(), "every Bloom 'maybe' is checked against the exact map");
        assertTrue(b.confirmed() >= 1);
        assertEquals("OK", s.status().get(0).status());
    }

    @Test
    @DisplayName("TI-2 (DROP via red-black floor): boundaries of each CIDR range are exact; malformed lines are skipped")
    void drop() {
        ThreatIntelService s = ThreatIntelServiceTestSupport.loaded(M);
        assertEquals("SBL256894", s.dropRange("1.10.16.0").sblId());
        assertEquals("SBL256894", s.dropRange("1.10.31.255").sblId(), "last address of the /20");
        assertNull(s.dropRange("1.10.32.0"), "first address after the range");
        assertNull(s.dropRange("1.10.15.255"));
        assertEquals("SBL459831", s.dropRange("2.56.195.7").sblId());
        assertNull(s.dropRange("1.1.1.1"));
        var st = s.status().get(1);
        assertEquals(2, st.records());
        assertEquals(java.time.Instant.ofEpochSecond(1795264004L).toString(), st.feedTimestamp());
    }

    @Test
    @DisplayName("TI-3 (failures): 503 x3 gives ERROR and keeps nothing fake; 404 is not retried; timeouts are retried")
    void failures() {
        AtomicInteger calls = new AtomicInteger();
        ThreatIntelService s = new ThreatIntelService(M, req -> {
            calls.incrementAndGet();
            return new ThreatIntelService.Response(503, "busy");
        }, Clock.systemUTC(), true, "", 0) {
            @Override
            long sleepMillis(int a) {
                return 0;
            }
        };
        s.refreshFeodo();
        assertEquals(3, calls.get());
        assertEquals("ERROR", s.status().get(0).status());
        assertTrue(s.status().get(0).error().contains("HTTP 503"));
        assertTrue(s.matches("50.16.16.211").isEmpty(), "no list -> no match is invented");

        AtomicInteger c2 = new AtomicInteger();
        ThreatIntelService s2 = new ThreatIntelService(M, req -> {
            c2.incrementAndGet();
            return new ThreatIntelService.Response(404, "");
        }, Clock.systemUTC(), true, "", 0);
        s2.refreshDrop();
        assertEquals(1, c2.get());

        AtomicInteger c3 = new AtomicInteger();
        ThreatIntelService s3 = new ThreatIntelService(M, req -> {
            if (c3.incrementAndGet() < 3) {
                throw new HttpTimeoutException("slow");
            }
            return new ThreatIntelService.Response(200, ThreatIntelServiceTestSupport.FEODO);
        }, Clock.systemUTC(), true, "", 0) {
            @Override
            long sleepMillis(int a) {
                return 0;
            }
        };
        s3.refreshFeodo();
        assertEquals("OK", s3.status().get(0).status());
    }

    @Test
    @DisplayName("TI-4 (privacy/SSRF): private and reserved addresses are never sent to RIPEstat or AbuseIPDB")
    void noExternalForPrivate() {
        AtomicInteger calls = new AtomicInteger();
        ThreatIntelService s = new ThreatIntelService(M, req -> {
            calls.incrementAndGet();
            return new ThreatIntelService.Response(200, "{}");
        }, Clock.systemUTC(), false, "dummy-key-for-test", 5);
        assertNull(s.asn("192.168.1.1"));
        assertNull(s.asn("169.254.169.254"));
        assertNull(s.abuse("10.0.0.1"));
        assertNull(s.asn("example.com"));
        assertEquals(0, calls.get());
        assertEquals("NOT_CONFIGURED", new ThreatIntelService(M, r -> null, Clock.systemUTC(), false, "", 0).status().get(3).status());
    }
}
