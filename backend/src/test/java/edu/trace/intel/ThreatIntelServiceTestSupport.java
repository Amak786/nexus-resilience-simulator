package edu.trace.intel;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;

/** Builds a ThreatIntelService loaded from small fixture feeds through the fake HTTP transport (no network). */
public final class ThreatIntelServiceTestSupport {

    public static final String FEODO = """
            [{"ip_address":"50.16.16.211","port":443,"status":"online","hostname":null,"as_number":14618,"as_name":"AMAZON-AES",
              "country":"US","first_seen":"2024-01-01 00:00:00","last_online":"2026-11-20","malware":"QakBot"},
             {"ip_address":"162.243.103.246","port":8080,"status":"offline","as_number":14061,"as_name":"DIGITALOCEAN-ASN",
              "country":"US","first_seen":"2022-06-04 21:24:53","last_online":"2026-03-07","malware":"Emotet"},
             {"ip_address":"not-an-ip","port":1,"status":"online"}]""";

    public static final String DROP = """
            {"cidr":"1.10.16.0/20","sblid":"SBL256894","rir":"apnic"}
            {"cidr":"2.56.192.0/22","sblid":"SBL459831","rir":"ripencc"}
            {"cidr":"bad/99","sblid":"x"}
            {"type":"metadata","timestamp":1795264004,"size":3,"records":2}
            """;

    private ThreatIntelServiceTestSupport() {}

    public static ThreatIntelService loaded(ObjectMapper m) {
        Deque<ThreatIntelService.Response> q = new ArrayDeque<>();
        q.add(new ThreatIntelService.Response(200, FEODO));
        q.add(new ThreatIntelService.Response(200, DROP));
        ThreatIntelService s = new ThreatIntelService(m, req -> q.isEmpty() ? new ThreatIntelService.Response(500, "") : q.poll(),
                Clock.systemUTC(), false, "", 0) {
            @Override
            long sleepMillis(int attempt) {
                return 0;
            }
        };
        s.refreshFeeds();
        return s;
    }
}
