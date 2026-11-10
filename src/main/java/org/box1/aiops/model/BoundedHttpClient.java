package org.box1.aiops.model;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.box1.aiops.BoundedHttp;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

/**
 * The LangChain4j HTTP transport for the model, sent through {@link BoundedHttp}: HTTP/1.1 only (vLLM rejects an h2c
 * upgrade offer with HTTP 400), no redirects, one deadline over headers and body, and a response cap. Only the
 * headers LangChain4j needs to describe the body are forwarded, so no credential reaches the model endpoint; trace
 * propagation headers are added by {@code decorate}. Streaming is not offered.
 */
final class BoundedHttpClient implements dev.langchain4j.http.client.HttpClient {
    static final int MAX_RESPONSE_BYTES = 262_144;
    private static final Set<String> FORWARDED_HEADERS = Set.of("content-type", "accept");

    /** A transport failure that is not an HTTP status: the outcome is already decided. */
    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final ModelResult.Outcome outcome;

        Failure(ModelResult.Outcome outcome, String detail) {
            super(detail, null, false, false);
            this.outcome = outcome;
        }

        ModelResult.Outcome outcome() {
            return outcome;
        }
    }

    private final HttpClient http;
    private final Duration timeout;
    private final Consumer<HttpRequest.Builder> decorate;

    BoundedHttpClient(Duration connectTimeout, Duration timeout, Consumer<HttpRequest.Builder> decorate) {
        this.timeout = timeout;
        this.decorate = decorate;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public SuccessfulHttpResponse execute(dev.langchain4j.http.client.HttpRequest request) {
        if (request.method() != HttpMethod.POST || request.body() == null) {
            throw new Failure(ModelResult.Outcome.INVALID, "unsupported model request");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url())).timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(request.body()));
        for (Map.Entry<String, List<String>> h : request.headers().entrySet()) {
            if (FORWARDED_HEADERS.contains(h.getKey().toLowerCase(java.util.Locale.ROOT))) {
                h.getValue().forEach(v -> builder.header(h.getKey(), v));
            }
        }
        decorate.accept(builder);
        BoundedHttp.Result response;
        try {
            response = BoundedHttp.send(http, builder.build(), MAX_RESPONSE_BYTES, timeout);
        } catch (BoundedHttp.Timeout e) {
            throw new Failure(ModelResult.Outcome.TIMEOUT, "model did not answer within " + timeout);
        } catch (BoundedHttp.Unreachable e) {
            throw new Failure(ModelResult.Outcome.UNAVAILABLE, "model endpoint unreachable");
        }
        if (response.status() < 200 || response.status() > 299) {
            // The body is model-server text: it is never carried into the exception.
            throw new HttpException(response.status(), "model endpoint HTTP " + response.status());
        }
        if (response.tooLarge()) {
            throw new Failure(ModelResult.Outcome.INVALID, "model response too large");
        }
        return SuccessfulHttpResponse.builder().statusCode(response.status()).body(response.body()).build();
    }

    @Override
    public void execute(dev.langchain4j.http.client.HttpRequest request, ServerSentEventParser parser,
            ServerSentEventListener listener) {
        throw new UnsupportedOperationException("streaming is not used");
    }

    /** Hands LangChain4j this preconfigured client; the timeouts it would set are the ones given here. */
    static final class Builder implements HttpClientBuilder {
        private final BoundedHttpClient client;
        private final Duration connectTimeout;
        private final Duration timeout;

        Builder(Duration connectTimeout, Duration timeout, Consumer<HttpRequest.Builder> decorate) {
            this.connectTimeout = connectTimeout;
            this.timeout = timeout;
            this.client = new BoundedHttpClient(connectTimeout, timeout, decorate);
        }

        @Override
        public Duration connectTimeout() {
            return connectTimeout;
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration ignored) {
            return this;
        }

        @Override
        public Duration readTimeout() {
            return timeout;
        }

        @Override
        public HttpClientBuilder readTimeout(Duration ignored) {
            return this;
        }

        @Override
        public dev.langchain4j.http.client.HttpClient build() {
            return client;
        }
    }
}
