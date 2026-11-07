package org.box1.aiops.window;

import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.TopicPartition;
import org.box1.aiops.AiopsConfig;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Identifier;
import io.smallrye.reactive.messaging.kafka.KafkaConsumerRebalanceListener;

/** On assignment, start every partition at now minus the window age, so a restart refills the window (D-007). */
@ApplicationScoped
@Identifier("aiops-window-start")
public class WindowStartSeek implements KafkaConsumerRebalanceListener {
    private final AiopsConfig config;
    private final MeterRegistry registry;

    @Inject
    public WindowStartSeek(AiopsConfig config, MeterRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    @Override
    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        try {
            seek(consumer, partitions, Clock.systemUTC().instant().minus(config.window().age()).toEpochMilli());
        } catch (RuntimeException e) {
            // The consumer falls back to auto.offset.reset=latest: the window then fills from now (review M-6).
            registry.counter("aiops_window_seek_failures_total").increment();
            Log.warnf("window start seek failed (%s); the window fills from now", e.getClass().getSimpleName());
        }
    }

    static void seek(Consumer<?, ?> consumer, Collection<TopicPartition> partitions, long since) {
        Map<TopicPartition, Long> query = new HashMap<>();
        partitions.forEach(p -> query.put(p, since));
        Map<TopicPartition, OffsetAndTimestamp> offsets = consumer.offsetsForTimes(query);
        for (TopicPartition partition : partitions) {
            OffsetAndTimestamp found = offsets.get(partition);
            if (found != null) {
                consumer.seek(partition, found.offset());
            } else {
                consumer.seekToEnd(List.of(partition));
            }
        }
        Log.infof("window start: %d partitions positioned at %d", partitions.size(), since);
    }
}
