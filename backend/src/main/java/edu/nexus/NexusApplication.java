package edu.nexus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** NEXUS: Infrastructure Failure &amp; Resilience Simulator (academic project, synthetic data). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NexusApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusApplication.class, args);
    }
}
