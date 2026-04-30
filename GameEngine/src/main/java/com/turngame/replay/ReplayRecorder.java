package com.turngame.replay;

import com.google.gson.Gson;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class ReplayRecorder implements AutoCloseable {
    private static final Gson GSON = new Gson();

    private final BufferedWriter writer;

    public ReplayRecorder(String matchId) {
        try {
            Path dir = Path.of("replays");
            Files.createDirectories(dir);
            Path file = dir.resolve(matchId + ".jsonl");
            this.writer = Files.newBufferedWriter(file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to initialize replay recorder", ex);
        }
    }

    public synchronized void record(String eventType, Map<String, Object> payload) {
        try {
            Map<String, Object> row = new HashMap<>();
            row.put("ts", Instant.now().toString());
            row.put("type", eventType);
            row.put("payload", payload);
            writer.write(GSON.toJson(row));
            writer.newLine();
            writer.flush();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write replay event", ex);
        }
    }

    @Override
    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException ignored) {
        }
    }
}
