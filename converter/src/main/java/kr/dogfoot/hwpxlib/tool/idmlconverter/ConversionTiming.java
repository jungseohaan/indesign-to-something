package kr.dogfoot.hwpxlib.tool.idmlconverter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight per-conversion timing/counter collector.
 *
 * <p>The collector is intentionally observational: it records durations and
 * aggregate counters but never participates in ownership or placement decisions.</p>
 */
public final class ConversionTiming {
    private static final ThreadLocal<ConversionTiming> CURRENT = new ThreadLocal<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final long startedNanos;
    private final long startedMillis;
    private final Map<String, Object> metadata = new LinkedHashMap<>();
    private final Map<String, Object> metrics = new LinkedHashMap<>();
    private final Map<String, Long> counters = new LinkedHashMap<>();
    private final List<Map<String, Object>> events = new ArrayList<>();

    private String status = "running";
    private String errorType;
    private String errorMessage;

    private ConversionTiming(String idmlPath, String hwpxPath, String resolvedJsonPath) {
        this.startedNanos = System.nanoTime();
        this.startedMillis = System.currentTimeMillis();
        metadata.put("idmlPath", idmlPath);
        metadata.put("hwpxPath", hwpxPath);
        if (resolvedJsonPath != null) {
            metadata.put("resolvedJsonPath", resolvedJsonPath);
            File f = new File(resolvedJsonPath);
            if (f.exists()) {
                metrics.put("resolvedJsonBytes", f.length());
            }
        }
    }

    public static ConversionTiming start(String idmlPath, String hwpxPath, String resolvedJsonPath) {
        ConversionTiming timing = new ConversionTiming(idmlPath, hwpxPath, resolvedJsonPath);
        CURRENT.set(timing);
        return timing;
    }

    public static ConversionTiming current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Scope time(String name) {
        ConversionTiming timing = current();
        return timing != null ? timing.scope(name) : Scope.NOOP;
    }

    public static void metric(String name, Object value) {
        ConversionTiming timing = current();
        if (timing != null) {
            timing.metrics.put(name, value);
        }
    }

    public static void addCounter(String name, long delta) {
        ConversionTiming timing = current();
        if (timing != null) {
            Long old = timing.counters.get(name);
            timing.counters.put(name, (old == null ? 0L : old) + delta);
        }
    }

    public Scope scope(String name) {
        return new Scope(this, name);
    }

    public void succeeded() {
        this.status = "success";
    }

    public void failed(Throwable t) {
        this.status = "failed";
        if (t != null) {
            this.errorType = t.getClass().getName();
            this.errorMessage = t.getMessage();
        }
    }

    public void writeAdjacentTo(String hwpxPath) {
        try {
            Path out = timingPath(hwpxPath);
            Files.createDirectories(out.getParent());
            Files.write(out, GSON.toJson(toJsonMap()).getBytes(StandardCharsets.UTF_8));
            System.err.println("[ConversionTiming] wrote " + out);
        } catch (Exception e) {
            System.err.println("[ConversionTiming] write failed: " + e.getMessage());
        }
    }

    private Path timingPath(String hwpxPath) {
        File hwpx = new File(hwpxPath);
        File parent = hwpx.getAbsoluteFile().getParentFile();
        if (parent == null) {
            parent = new File(".");
        }
        return new File(parent, "conversion-timing.json").toPath();
    }

    private Map<String, Object> toJsonMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("status", status);
        root.put("startedAtEpochMs", startedMillis);
        root.put("totalMs", elapsedMs(startedNanos, System.nanoTime()));
        root.put("metadata", metadata);
        root.put("metrics", metrics);
        root.put("counters", counters);
        root.put("events", events);
        if (errorType != null) root.put("errorType", errorType);
        if (errorMessage != null) root.put("errorMessage", errorMessage);
        return root;
    }

    private void addEvent(String name, long eventStartedNanos, long eventEndedNanos) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("name", name);
        event.put("startMs", elapsedMs(startedNanos, eventStartedNanos));
        event.put("durationMs", elapsedMs(eventStartedNanos, eventEndedNanos));
        events.add(event);
    }

    private static double elapsedMs(long fromNanos, long toNanos) {
        return Math.round((toNanos - fromNanos) / 10000.0) / 100.0;
    }

    public static final class Scope implements AutoCloseable {
        private static final Scope NOOP = new Scope(null, null);

        private final ConversionTiming timing;
        private final String name;
        private final long startedNanos;
        private boolean closed;

        private Scope(ConversionTiming timing, String name) {
            this.timing = timing;
            this.name = name;
            this.startedNanos = System.nanoTime();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (timing != null && name != null) {
                timing.addEvent(name, startedNanos, System.nanoTime());
            }
        }
    }
}
