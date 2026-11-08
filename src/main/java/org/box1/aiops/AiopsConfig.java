package org.box1.aiops;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** All runtime limits of the service (box1-docs#20 D-003, D-006, D-007, D-008). */
@ConfigMapping(prefix = "aiops")
public interface AiopsConfig {

    Upstream upstream();

    Model model();

    Analysis analysis();

    Chat chat();

    Window window();

    Evidence evidence();

    Platform platform();

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

    /** The Assistant's chat turns (D-016). They share the model queue with analyses. */
    interface Chat {
        @WithDefault("200")
        int maxConversations();

        /** Idle time after which a conversation is forgotten. */
        @WithDefault("2h")
        Duration ttl();

        @WithDefault("20")
        int maxTurns();

        @WithDefault("2000")
        int messageMaxChars();

        /** Tool calls per turn; further calls are answered with a limit error and the model must answer. */
        @WithDefault("8")
        int maxToolCalls();

        /** Model calls per turn that may request tools; one final call without tools may follow. */
        @WithDefault("4")
        int maxModelRounds();

        /** Wall-clock limit for one turn, including every model and tool call. */
        @WithDefault("90s")
        Duration turnDeadline();

        @WithDefault("1024")
        int answerMaxTokens();
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

    /**
     * Platform evidence (box1-docs#111): Tempo traces, LokiStack logs, Thanos and Alertmanager tenancy reads, with the
     * pod's own projected ServiceAccount token over TLS that trusts only the service CA. An unset URL or token leaves
     * that source UNAVAILABLE ("not configured").
     */
    interface Platform {
        Optional<String> tokenPath();

        Optional<String> caPath();

        Optional<String> tempoUrl();

        Optional<String> lokiUrl();

        Optional<String> thanosTenancyUrl();

        Optional<String> alertmanagerTenancyUrl();

        /** The namespaces an order's evidence covers; never taken from callers or the model. */
        @WithDefault("order-service-dev,inventory-service-dev,fulfillment-adapter-dev")
        List<String> orderNamespaces();

        /** Raw platform responses are larger than service JSON; they are flattened before they become evidence. */
        @WithDefault("524288")
        int maxResponseBytes();

        @WithDefault("8s")
        Duration readTimeout();

        @WithDefault("50")
        int logLimit();

        /** How far back a log search may reach (LokiStack keeps 3 days). */
        @WithDefault("72h")
        Duration logLookback();
    }

    interface Auth {
        List<String> allowedAzp();

        @WithDefault("true")
        boolean requireJwt();
    }
}
