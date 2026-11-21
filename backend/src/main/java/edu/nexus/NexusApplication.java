package edu.nexus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Nexus Resilience: Silent Shield - road-network snapshot integrity and resilience analysis on OpenStreetMap data (academic prototype). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NexusApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusApplication.class, args);
    }
}
