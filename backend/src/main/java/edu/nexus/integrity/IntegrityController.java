package edu.nexus.integrity;

import edu.nexus.integrity.IntegrityDtos.IntegrityRequest;
import edu.nexus.integrity.IntegrityDtos.IntegrityResponse;
import edu.nexus.integrity.IntegrityDtos.ScenarioResponse;
import edu.nexus.mumbai.MumbaiDtos.Area;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Silent Shield: trusted-baseline vs updated snapshot integrity check. */
@Tag(name = "Integrity check")
@RestController
@RequestMapping("/api/integrity")
public class IntegrityController {

    private final IntegrityService service;

    public IntegrityController(IntegrityService service) {
        this.service = service;
    }

    @Operation(summary = "Compare the trusted baseline with the updated snapshot (plus optional DEMONSTRATION modifications)")
    @PostMapping("/analyse")
    public IntegrityResponse analyse(@RequestBody IntegrityRequest request) {
        return service.analyse(request);
    }

    @Operation(summary = "Reproducible demonstration tampering scenario for an area and probe route (synthetic, not a real attack)")
    @GetMapping("/scenario")
    public ScenarioResponse scenario(@RequestParam double south, @RequestParam double west, @RequestParam double north,
                                     @RequestParam double east, @RequestParam double fromLat, @RequestParam double fromLon,
                                     @RequestParam double toLat, @RequestParam double toLon) {
        return service.scenario(new Area(south, west, north, east), fromLat, fromLon, toLat, toLon);
    }
}
