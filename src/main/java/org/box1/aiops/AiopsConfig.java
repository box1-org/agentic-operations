package org.box1.aiops;

import java.time.Duration;
import java.util.List;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** All runtime limits of the service (box1-docs#20 D-003, D-006, D-007, D-008). */
@ConfigMapping(prefix = "aiops")
public interface AiopsConfig {

    Upstream upstream();

    Model model();

    Analysis analysis();

    Window window();

    Evidence evidence();

    Auth auth();

    interface Upstream {
        String orderUrl();

        String inventoryUrl();

        String fulfillmentUrl();

        @WithDefault("3s")
        Duration connectTimeout();

        @WithDefault("5s")
        Duration readTimeout();
    }

    interface Model {
        String baseUrl();

        String id();

        @WithDefault("10s")
        Duration connectTimeout();

        @WithDefault("120s")
        Duration timeout();

        /** {@code 32k} (default) or {@code 16k}; sets the evidence and output budget. */
        @WithDefault("32k")
        String contextProfile();
    }

    interface Analysis {
        @WithDefault("200")
        int maxEntries();

        @WithDefault("24h")
        Duration ttl();

        /** In-flight model calls; matches vLLM {@code --max-num-seqs 1}. */
        @WithDefault("1")
        int concurrency();

        @WithDefault("4")
        int queueBound();

        /** Same default as the console's {@code console.stuck-threshold}. */
        @WithDefault("10m")
        Duration stuckThreshold();
    }

    interface Window {
        @WithDefault("6h")
        Duration age();

        @WithDefault("20000")
        int maxEvents();

        @WithDefault("200")
        int maxPerKey();
    }

    interface Evidence {
        @WithDefault("16384")
        int sourceMaxBytes();

        @WithDefault("50")
        int timelineLimit();

        /** Telemetry alerts need an asset-service read grant that v1 does not have (Q-5). */
        @WithDefault("false")
        boolean telemetryEnabled();
    }

    interface Auth {
        List<String> allowedAzp();

        @WithDefault("true")
        boolean requireJwt();
    }
}
