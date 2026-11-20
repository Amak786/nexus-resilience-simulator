package edu.nexus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** NEXUS - Mumbai Road Network Intelligence (academic prototype on OpenStreetMap data). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NexusApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusApplication.class, args);
    }
}
