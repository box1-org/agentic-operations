package org.box1.aiops.window;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.TopicPartition;
import org.box1.aiops.AiopsConfig;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Review M-6: seek to the window start; a failed lookup is counted and the consumer keeps going. */
class WindowStartSeekTest {
    static final TopicPartition P0 = new TopicPartition("box1.order.events.v1", 0);
    static final TopicPartition P1 = new TopicPartition("box1.order.events.v1", 1);

    @SuppressWarnings("unchecked")
    static Consumer<byte[], byte[]> consumer(List<String> calls, boolean fail) {
        return (Consumer<byte[], byte[]>) Proxy.newProxyInstance(Consumer.class.getClassLoader(),
            new Class<?>[] {Consumer.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "offsetsForTimes" -> {
                        if (fail) {
                            throw new org.apache.kafka.common.errors.TimeoutException("broker slow");
                        }
                        return Map.of(P0, new OffsetAndTimestamp(42L, 1L));
                    }
                    case "seek" -> calls.add("seek " + args[0] + " " + args[1]);
                    case "seekToEnd" -> calls.add("end " + ((Collection<?>) args[0]).iterator().next());
                    default -> {
                    }
                }
                return null;
            });
    }

    @Test
    void partitionsStartAtTheWindowOrAtTheEnd() {
        List<String> calls = new ArrayList<>();
        WindowStartSeek.seek(consumer(calls, false), List.of(P0, P1), 1000L);
        assertEquals(List.of("seek " + P0 + " 42", "end " + P1), calls);
    }

    @Test
    void aFailedLookupIsCountedAndDoesNotThrow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiopsConfig config = (AiopsConfig) Proxy.newProxyInstance(AiopsConfig.class.getClassLoader(),
            new Class<?>[] {AiopsConfig.class}, (p, m, a) -> m.getName().equals("window")
                ? Proxy.newProxyInstance(AiopsConfig.class.getClassLoader(), new Class<?>[] {AiopsConfig.Window.class},
                    (p2, m2, a2) -> java.time.Duration.ofHours(6))
                : null);
        List<String> calls = new ArrayList<>();
        new WindowStartSeek(config, registry).onPartitionsAssigned(consumer(calls, true), List.of(P0));
        assertEquals(1.0, registry.counter("aiops_window_seek_failures_total").count());
        assertTrue(calls.isEmpty());
    }
}
