package edu.nexus.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full Spring context over HTTP: Mumbai endpoints, JSON errors, CORS and size limits. */
@SpringBootTest(properties = {"nexus.cors-allowed-origins=https://nexus-demo.example", "nexus.max-request-bytes=200000"})
@AutoConfigureMockMvc
class ApiIntegrationTest {

    private static final String AREA = "\"area\":{\"south\":19.01,\"west\":72.83,\"north\":19.06,\"east\":72.87}";

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("API-1: health, algorithm catalog, provenance and OpenAPI are served")
    void systemEndpoints() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/algorithms")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(9));
        mvc.perform(get("/api/mumbai/provenance")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[?(@.id=='live-traffic')].dataClass").value("NOT_AVAILABLE"));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("API-2: network, route, components, critical links, events and tour compute on the real snapshot")
    void mumbaiEndpoints() throws Exception {
        mvc.perform(get("/api/mumbai/network?south=19.01&west=72.83&north=19.06&east=72.87")).andExpect(status().isOk())
                .andExpect(jsonPath("$.segments").isNumber());
        mvc.perform(post("/api/mumbai/route").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromLat\":19.018,\"fromLon\":72.847,\"toLat\":19.056,\"toLon\":72.845,\"metric\":\"TIME\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.queuesAgree").value(true));
        mvc.perform(post("/api/mumbai/components").contentType(MediaType.APPLICATION_JSON).content("{" + AREA + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.after.componentCount").isNumber());
        mvc.perform(post("/api/mumbai/critical-links").contentType(MediaType.APPLICATION_JSON)
                        .content("{" + AREA + ",\"sourceLat\":19.018,\"sourceLon\":72.847,\"sinkLat\":19.048,\"sinkLon\":72.840}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.maxFlowEqualsMinCut").value(true));
        mvc.perform(get("/api/mumbai/events?from=2023-01-01&to=2023-12-31")).andExpect(status().isOk())
                .andExpect(jsonPath("$.index.invariantsHold").value(true));
        mvc.perform(get("/api/mumbai/events/stats?from=2020-01-01&to=2025-12-31")).andExpect(status().isOk())
                .andExpect(jsonPath("$.matchesScan").value(true));
        mvc.perform(post("/api/mumbai/tour").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stops\":[{\"label\":\"A\",\"lat\":19.018,\"lon\":72.847},{\"label\":\"B\",\"lat\":19.043,\"lon\":72.864},{\"label\":\"C\",\"lat\":19.041,\"lon\":72.841}],\"startIndex\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tour[0]").value("A"));
        mvc.perform(post("/api/selfcheck")).andExpect(status().isOk()).andExpect(jsonPath("$.allPassed").value(true));
    }

    @Test
    @DisplayName("API-3: malformed JSON, unknown fields, wrong types and validation errors return 400 JSON")
    void errors() throws Exception {
        mvc.perform(post("/api/mumbai/route").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        mvc.perform(post("/api/lab/dsu").contentType(MediaType.APPLICATION_JSON).content("{\"elementCount\":5,\"hack\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/mumbai/components").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"area\":{\"south\":19.1,\"west\":72.8,\"north\":19.0,\"east\":72.9}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.issues[0].field").value("area"));
        mvc.perform(get("/api/mumbai/events?from=yesterday")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/nope")).andExpect(status().isNotFound());
        mvc.perform(get("/api/mumbai/route")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("API-4: bodies over the configured limit are rejected with 413")
    void payloadTooLarge() throws Exception {
        String big = "{\"values\":[" + "1,".repeat(150_000) + "1]}";
        mvc.perform(post("/api/lab/quickselect").contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    @DisplayName("API-5: CORS allows only the configured origin")
    void cors() throws Exception {
        mvc.perform(options("/api/health").header("Origin", "https://nexus-demo.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://nexus-demo.example"));
        mvc.perform(options("/api/health").header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
