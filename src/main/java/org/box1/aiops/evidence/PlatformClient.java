package org.box1.aiops.evidence;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Optional;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.box1.aiops.AiopsConfig;
import org.box1.aiops.BoundedHttp;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;

/**
 * Read-only GETs to the platform's observability gateways (box1-docs#111) with the pod's own projected
 * ServiceAccount token. The token is re-read when the kubelet rotates it and never logged. TLS trusts only the
 * mounted service CA; plain http is accepted only for in-process test stand-ins. Bounded like every upstream read.
 */
final class PlatformClient {
    private final AiopsConfig.Platform cfg;
    private final ObjectMapper json;
    private final OpenTelemetry otel;
    private final Duration connectTimeout;
    private volatile HttpClient http;
    private volatile FileTime caStamp;
    private volatile String token;
    private volatile FileTime tokenStamp;

    PlatformClient(AiopsConfig.Platform cfg, Duration connectTimeout, ObjectMapper json, OpenTelemetry otel) {
        this.cfg = cfg;
        this.connectTimeout = connectTimeout;
        this.json = json;
        this.otel = otel;
    }

    /** A GET of {@code base + pathAndQuery}; the caller builds the path only from validated values. */
    Fetch get(Optional<String> base, String what, String pathAndQuery) {
        if (base.isEmpty() || cfg.tokenPath().isEmpty()) {
            return Fetch.of(Fetch.Status.UNAVAILABLE, what + " is not configured for this service");
        }
        String bearer;
        HttpClient client;
        try {
            bearer = token();
            client = client(base.get());
        } catch (IOException | GeneralSecurityException e) {
            return Fetch.of(Fetch.Status.UNAVAILABLE, what + ": the service's platform credentials could not be read");
        }
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(base.get() + pathAndQuery)).timeout(cfg.readTimeout())
                .header("Accept", "application/json").header("Authorization", "Bearer " + bearer).GET();
        } catch (IllegalArgumentException e) {
            return Fetch.of(Fetch.Status.ERROR, what + ": request could not be built");
        }
        otel.getPropagators().getTextMapPropagator().inject(Context.current(), builder, HttpRequest.Builder::header);
        BoundedHttp.Result result;
        try {
            result = BoundedHttp.send(client, builder.build(), cfg.maxResponseBytes(), cfg.readTimeout());
        } catch (BoundedHttp.Timeout e) {
            return Fetch.of(Fetch.Status.TIMEOUT, what + " timed out");
        } catch (BoundedHttp.Unreachable e) {
            return Fetch.of(Fetch.Status.UNREACHABLE, what + " unreachable");
        }
        int status = result.status();
        if (status == 404) {
            return Fetch.of(Fetch.Status.NOT_FOUND, what + ": not found");
        }
        if (status == 401 || status == 403) {
            return Fetch.of(Fetch.Status.UNAUTHORIZED, what + " refused the service's read (HTTP " + status + ")");
        }
        if (status != 200) {
            return Fetch.of(Fetch.Status.ERROR, what + " HTTP " + status);
        }
        if (result.tooLarge()) {
            return Fetch.of(Fetch.Status.ERROR, what + " response larger than " + cfg.maxResponseBytes() + " bytes");
        }
        try {
            return Fetch.ok(json.readTree(result.body()));
        } catch (IOException e) {
            return Fetch.of(Fetch.Status.ERROR, what + " response is not JSON");
        }
    }

    private String token() throws IOException {
        Path path = Path.of(cfg.tokenPath().orElseThrow());
        FileTime stamp = Files.getLastModifiedTime(path);
        if (token == null || !stamp.equals(tokenStamp)) {
            String read = Files.readString(path, StandardCharsets.US_ASCII).strip();
            if (read.isEmpty() || !read.matches("^[A-Za-z0-9._-]+$")) {
                throw new IOException("token file is empty or malformed");
            }
            token = read;
            tokenStamp = stamp;
        }
        return token;
    }

    private HttpClient client(String base) throws IOException, GeneralSecurityException {
        boolean tls = base.startsWith("https://");
        FileTime stamp = tls ? Files.getLastModifiedTime(Path.of(cfg.caPath().orElseThrow(
            () -> new IOException("https needs a CA bundle")))) : null;
        HttpClient current = http;
        if (current != null && (!tls || stamp.equals(caStamp))) {
            return current;
        }
        HttpClient.Builder b = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER);
        if (tls) {
            b.sslContext(trustOnly(Path.of(cfg.caPath().orElseThrow())));
            caStamp = stamp;
        }
        HttpClient built = b.build();
        http = built;
        return built;
    }

    /** An SSL context that trusts exactly the certificates in the PEM bundle, and nothing from the JDK store. */
    static SSLContext trustOnly(Path pem) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        try (InputStream in = Files.newInputStream(pem)) {
            int i = 0;
            for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                store.setCertificateEntry("service-ca-" + i++, c);
            }
            if (i == 0) {
                throw new GeneralSecurityException("no certificate in the CA bundle");
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }
}
