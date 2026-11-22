package edu.trace.net;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Optional persistence. When TRACE_DATA_DIR is set, the event store is written to {@code trace-events.json} every minute
 * (write to a temporary file, then atomic move) and reloaded on start-up, so a local or VPS deployment survives restarts.
 * Without TRACE_DATA_DIR nothing is written to disk. On Render's free plan the disk is ephemeral, so data is still lost on redeploy.
 */
@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    private final EventStore store;
    private final ObjectMapper mapper;
    private final Path file;
    private long savedVersion = -1;

    public SnapshotService(EventStore store, ObjectMapper mapper, @Value("${TRACE_DATA_DIR:}") String dir) {
        this.store = store;
        this.mapper = mapper;
        this.file = dir == null || dir.isBlank() ? null : Path.of(dir.strip()).resolve("trace-events.json");
    }

    public boolean enabled() {
        return file != null;
    }

    public String location() {
        return file == null ? null : file.toAbsolutePath().toString();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            int n = store.restore(mapper.readValue(file.toFile(), EventStore.Snapshot.class));
            savedVersion = store.version();
            log.info("restored {} flows from {}", n, file);
        } catch (IOException | RuntimeException e) {
            log.warn("could not read snapshot {}: {}", file, e.getMessage());
        }
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void save() {
        if (file == null || store.version() == savedVersion) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("trace-events.json.tmp");
            long v = store.version();
            mapper.writeValue(tmp.toFile(), store.snapshot());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            savedVersion = v;
        } catch (IOException | RuntimeException e) {
            log.warn("could not write snapshot {}: {}", file, e.getMessage());
        }
    }
}
