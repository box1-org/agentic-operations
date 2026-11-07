package org.box1.aiops;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One HTTP exchange with an overall deadline that covers the body as well as the headers, and a body read that stops
 * at {@code maxBytes + 1}. A server that sends headers and then stalls can no longer hold a worker thread (review S-1,
 * S-7).
 */
public final class BoundedHttp {

    /** Outcome of an exchange; {@code body} is null when {@code tooLarge}. */
    public record Result(int status, byte[] body, boolean tooLarge) {
    }

    /** The deadline passed. */
    public static class Timeout extends Exception {
        public Timeout() {
            super(null, null, false, false);
        }
    }

    /** Connection refused, reset, or another transport failure. */
    public static class Unreachable extends Exception {
        public Unreachable() {
            super(null, null, false, false);
        }
    }

    private BoundedHttp() {
    }

    public static Result send(HttpClient client, HttpRequest request, int maxBytes, Duration deadline)
            throws Timeout, Unreachable {
        CompletableFuture<HttpResponse<Capped>> future = client.sendAsync(request, info -> new Limited(maxBytes));
        try {
            HttpResponse<Capped> response = future.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
            Capped capped = response.body();
            return new Result(response.statusCode(), capped.tooLarge ? null : capped.bytes, capped.tooLarge);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new Timeout();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof HttpTimeoutException) {
                throw new Timeout();
            }
            throw new Unreachable();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new Unreachable();
        }
    }

    record Capped(byte[] bytes, boolean tooLarge) {
    }

    /** Collects at most {@code max} bytes, then cancels the subscription and reports the body as too large. */
    static final class Limited implements HttpResponse.BodySubscriber<Capped> {
        private final int max;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final CompletableFuture<Capped> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        Limited(int max) {
            this.max = max;
        }

        @Override
        public CompletionStage<Capped> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer b : items) {
                byte[] chunk = new byte[b.remaining()];
                b.get(chunk);
                out.write(chunk, 0, chunk.length);
                if (out.size() > max) {
                    result.complete(new Capped(null, true));
                    subscription.cancel();
                    return;
                }
            }
        }

        @Override
        public void onError(Throwable t) {
            result.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            result.complete(new Capped(out.toByteArray(), false));
        }
    }
}
