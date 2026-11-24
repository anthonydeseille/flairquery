package io.github.anthonydeseille.flairquery.collector.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Minimal read-only Kubernetes API client (GET only), authenticated with the pod's service account. No client
 * library: the collector reads a handful of paths (pods of its namespace, the kubelet's cAdvisor metrics through the
 * API server proxy).
 */
public final class KubeClient {

    static final Path SA = Path.of("/var/run/secrets/kubernetes.io/serviceaccount");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI base;
    private final Supplier<String> token;
    private final HttpClient http;

    public KubeClient(URI base, Supplier<String> token, SSLContext ssl) {
        this.base = URI.create(base.toString().replaceAll("/+$", ""));
        this.token = token;
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3));
        if (ssl != null) b.sslContext(ssl);
        this.http = b.build();
    }

    /** True when running in a pod with a service account token mounted. */
    public static boolean inClusterAvailable(Map<String, String> env) {
        return env.get("KUBERNETES_SERVICE_HOST") != null && Files.isReadable(SA.resolve("token"));
    }

    /** The in-cluster API server, the mounted CA; the token is re-read on every call (bound tokens rotate). */
    public static KubeClient inCluster(Map<String, String> env) throws Exception {
        String host = env.get("KUBERNETES_SERVICE_HOST");
        String port = env.getOrDefault("KUBERNETES_SERVICE_PORT", "443");
        if (host.contains(":")) host = "[" + host + "]";   // IPv6
        return new KubeClient(URI.create("https://" + host + ":" + port), KubeClient::readToken,
                sslContext(SA.resolve("ca.crt")));
    }

    /** Namespace of the collector's own pod. */
    public static String ownNamespace() {
        try {
            return Files.readString(SA.resolve("namespace")).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static String readToken() {
        try {
            return Files.readString(SA.resolve("token")).trim();
        } catch (Exception e) {
            throw new IllegalStateException("service account token not readable", e);
        }
    }

    static SSLContext sslContext(Path caFile) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        try (InputStream in = Files.newInputStream(caFile)) {
            int i = 0;
            for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                ks.setCertificateEntry("ca-" + i++, c);
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    public String get(String path) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10)).GET();
        String t = token == null ? null : token.get();
        if (t != null && !t.isEmpty()) req.header("Authorization", "Bearer " + t);
        HttpResponse<String> r = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            String body = r.body() == null ? "" : r.body();
            throw new IllegalStateException("Kubernetes API answered HTTP " + r.statusCode() + " on " + path + ": "
                    + body.substring(0, Math.min(300, body.length())));
        }
        return r.body();
    }

    public JsonNode getJson(String path) throws Exception {
        return JSON.readTree(get(path));
    }
}
