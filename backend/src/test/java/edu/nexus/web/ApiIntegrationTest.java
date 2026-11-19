package edu.nexus.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full Spring context over HTTP: routing, JSON errors, CORS and size limits. */
@SpringBootTest(properties = {"nexus.cors-allowed-origins=https://nexus-demo.example", "nexus.max-request-bytes=200000"})
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private String demo() throws Exception {
        MvcResult r = mvc.perform(get("/api/dataset/demo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.synthetic").value(true)).andReturn();
        return r.getResponse().getContentAsString();
    }

    @Test
    @DisplayName("API-1: health, algorithm catalog and OpenAPI document are served")
    void systemEndpoints() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/algorithms")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(8));
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("API-2: flow and failure-lab endpoints compute from the posted dataset")
    void computeEndpoints() throws Exception {
        String ds = demo();
        mvc.perform(post("/api/flow/optimize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataset\":" + ds + ",\"algorithm\":\"COMPARE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.maxFlowEqualsMinCut").value(true));
        mvc.perform(post("/api/failure-lab/simulate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataset\":" + ds + ",\"failedRoadIds\":[\"R22\"],\"batchScenarios\":20}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.newlyIsolatedZones[0]").value("Z10"));
        mvc.perform(post("/api/selfcheck")).andExpect(status().isOk()).andExpect(jsonPath("$.allPassed").value(true));
    }

    @Test
    @DisplayName("API-3: malformed JSON, unknown fields, wrong types and validation errors return 400 JSON without stack traces")
    void errors() throws Exception {
        mvc.perform(post("/api/flow/optimize").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        mvc.perform(post("/api/lab/dsu").contentType(MediaType.APPLICATION_JSON).content("{\"elementCount\":5,\"hack\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("MALFORMED_REQUEST"));
        mvc.perform(post("/api/lab/dsu").contentType(MediaType.APPLICATION_JSON).content("{\"elementCount\":\"5\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/lab/flow").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodes\":[\"s\",\"t\"],\"edges\":[{\"from\":\"s\",\"to\":\"t\",\"capacity\":-1}],\"source\":\"s\",\"sink\":\"t\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.issues[0].field").value("edges[0].capacity"));
        mvc.perform(get("/api/nope")).andExpect(status().isNotFound());
        mvc.perform(get("/api/flow/optimize")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/benchmarks/unknown")).andExpect(status().isBadRequest());
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
