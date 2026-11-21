package edu.trace;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** TRACE - Network Trust, Reputation and Connectivity Explorer (academic project). */
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class TraceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TraceApplication.class, args);
    }
}
