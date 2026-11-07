package org.box1.aiops.analysis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded in-memory analyses (D-003): at most {@code maxEntries}, each for {@code ttl} after creation. Nothing
 * survives a restart; a lost ID reads as not found. One replica only.
 */
public class AnalysisStore {
    private record Entry(Analysis analysis, String requestedBy, Instant expiresAt) {
    }

    private final Clock clock;
    private final Duration ttl;
    private final LinkedHashMap<String, Entry> entries;

    public AnalysisStore(Clock clock, Duration ttl, int maxEntries) {
        this.clock = clock;
        this.ttl = ttl;
        this.entries = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public synchronized void put(Analysis analysis, String requestedBy) {
        Entry existing = entries.get(analysis.analysisId());
        Instant expires = existing != null ? existing.expiresAt() : analysis.createdAt().plus(ttl);
        entries.put(analysis.analysisId(), new Entry(analysis, requestedBy, expires));
    }

    public synchronized Optional<Analysis> get(String id) {
        Entry e = entries.get(id);
        if (e == null) {
            return Optional.empty();
        }
        if (clock.instant().isAfter(e.expiresAt())) {
            entries.remove(id);
            return Optional.empty();
        }
        return Optional.of(e.analysis());
    }

    public synchronized void remove(String id) {
        entries.remove(id);
    }

    public synchronized int size() {
        return entries.size();
    }
}
