package org.box1.aiops.chat;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Bounded in-memory conversations (D-016): at most {@code maxEntries}, least recently used evicted first, forgotten
 * after {@code ttl} without activity, lost on restart by design. Each belongs to the subject that created it; any
 * other subject sees nothing, exactly as for an unknown ID.
 */
final class ConversationStore {
    private record Entry(Conversation conversation, String owner) {
    }

    private final Clock clock;
    private final Duration ttl;
    private final Map<String, Entry> entries;

    ConversationStore(Clock clock, Duration ttl, int maxEntries) {
        this.clock = clock;
        this.ttl = ttl;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > maxEntries;
            }
        };
    }

    synchronized void put(Conversation c, String owner) {
        entries.put(c.conversationId(), new Entry(c, owner));
    }

    /** The conversation if it exists, has not expired and belongs to {@code owner}. */
    synchronized Optional<Conversation> get(String id, String owner) {
        Entry e = entries.get(id);
        if (e == null) {
            return Optional.empty();
        }
        if (expired(e.conversation())) {
            entries.remove(id);
            return Optional.empty();
        }
        return owner.equals(e.owner()) ? Optional.of(e.conversation()) : Optional.empty();
    }

    /** Applies a change if the conversation is still held (a worker may outlive an eviction); returns the result. */
    synchronized Optional<Conversation> update(String id, UnaryOperator<Conversation> change) {
        Entry e = entries.get(id);
        if (e == null) {
            return Optional.empty();
        }
        Conversation next = change.apply(e.conversation());
        entries.put(id, new Entry(next, e.owner()));
        return Optional.of(next);
    }

    synchronized int size() {
        return entries.size();
    }

    private boolean expired(Conversation c) {
        boolean running = c.last() != null && !c.last().status().finished();
        return !running && c.updatedAt().plus(ttl).isBefore(clock.instant());
    }
}
