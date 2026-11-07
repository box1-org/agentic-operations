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

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Identifier;
import io.smallrye.reactive.messaging.kafka.KafkaConsumerRebalanceListener;

/** On assignment, start every partition at now minus the window age, so a restart refills the window (D-007). */
@ApplicationScoped
@Identifier("aiops-window-start")
public class WindowStartSeek implements KafkaConsumerRebalanceListener {
    private final AiopsConfig config;

    @Inject
    public WindowStartSeek(AiopsConfig config) {
        this.config = config;
    }

    @Override
    public void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        long since = Clock.systemUTC().instant().minus(config.window().age()).toEpochMilli();
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
        Log.infof("window start: %d partitions positioned at now-%s", partitions.size(), config.window().age());
    }
}
