package org.box1.aiops.analysis;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.box1.aiops.AiopsConfig;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;

/**
 * The one queue for model work (D-008, D-016). vLLM serves {@code --max-num-seqs 1}, so analyses and Assistant chat
 * turns share a fixed pool of {@code aiops.analysis.concurrency} workers and a bounded queue; when it is full the
 * caller answers BUSY. Work is in memory only: a restart abandons it, by design.
 */
@ApplicationScoped
public class ModelGate {
    private final ThreadPoolExecutor workers;
    private final MeterRegistry registry;

    @Inject
    public ModelGate(AiopsConfig config, MeterRegistry registry) {
        this.registry = registry;
        AiopsConfig.Analysis a = config.analysis();
        this.workers = new ThreadPoolExecutor(a.concurrency(), a.concurrency(), 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(Math.max(1, a.queueBound())), r -> {
                Thread t = new Thread(r, "aiops-model");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
        Gauge.builder("aiops_queue_depth", workers, w -> w.getQueue().size())
            .description("Analyses and chat turns waiting for the model").register(registry);
    }

    /** Queues the work, or returns false when the queue is full (the caller answers BUSY and stores nothing). */
    public boolean tryExecute(Runnable task) {
        try {
            workers.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    @PreDestroy
    void stop() {
        int dropped = workers.shutdownNow().size();
        int running = workers.getActiveCount();
        if (dropped + running > 0) {
            registry.counter("aiops_model_work_abandoned_total").increment(dropped + running);
            Log.warnf("shutdown abandoned %d queued and %d running model jobs (in-memory, by design)", dropped,
                running);
        }
    }
}
