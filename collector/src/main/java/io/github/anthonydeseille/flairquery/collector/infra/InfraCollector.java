package io.github.anthonydeseille.flairquery.collector.infra;

import io.github.anthonydeseille.flairquery.collector.log.Log;
import io.github.anthonydeseille.flairquery.collector.source.PostgresSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the infrastructure signals once per collection cycle: the observed PostgreSQL server, the Kubernetes pods
 * and their cAdvisor usage (when running in a cluster), and the JVM of every agent (every replica found in Kubernetes,
 * otherwise the configured agent URL). Each source is an enrichment: a failure is logged and the others are kept.
 */
public class InfraCollector {   // not final: tests stub read()

    private final PostgresSource postgres;
    private final KubernetesSource kubernetes;   // null: not in a cluster or disabled
    private final String agentUrl;               // fallback when Kubernetes is not read; null: none
    private final JvmSource jvm = new JvmSource();

    private volatile int kubernetesUp = -1;
    private volatile int jvmRead;
    private volatile int jvmFailed;

    public InfraCollector(PostgresSource postgres, KubernetesSource kubernetes, String agentUrl) {
        this.postgres = postgres;
        this.kubernetes = kubernetes;
        this.agentUrl = agentUrl;
    }

    /** -1: Kubernetes not configured, 0: not read on the last cycle, 1: read. */
    public int kubernetesUp() { return kubernetesUp; }
    public int jvmRead() { return jvmRead; }
    public int jvmFailed() { return jvmFailed; }
    public boolean kubernetesEnabled() { return kubernetes != null; }

    /** Never throws. */
    public List<InfraSample> read() {
        List<InfraSample> out = new ArrayList<>();
        try {
            out.add(new InfraSample(InfraSample.POSTGRES, postgres.target().name(), postgres.readServer()));
        } catch (Exception e) {
            Log.warn("PostgreSQL settings not readable this cycle", e, "target", postgres.target().name());
        }

        List<KubernetesSource.Agent> agents = new ArrayList<>();
        if (kubernetes != null) {
            try {
                KubernetesSource.Reading r = kubernetes.read();
                out.addAll(r.containers());
                agents.addAll(r.agents());
                kubernetesUp = 1;
            } catch (Exception e) {
                kubernetesUp = 0;
                Log.warn("Kubernetes API not readable this cycle (pod resources and usage unavailable)", e,
                        "namespace", kubernetes.namespace());
            }
        } else if (agentUrl != null) {
            agents.add(new KubernetesSource.Agent(null, null, null, agentUrl));
        }

        int ok = 0;
        int failed = 0;
        for (KubernetesSource.Agent a : agents) {
            String subject = a.pod() == null ? a.baseUrl() : a.namespace() + "/" + a.pod();
            try {
                out.add(jvm.read(a.baseUrl(), subject, a.workload()));
                ok++;
            } catch (Exception e) {
                failed++;
                Log.warn("agent JVM metrics not readable this cycle", e, "agent", a.baseUrl(), "pod", subject);
            }
        }
        jvmRead = ok;
        jvmFailed = failed;
        return out;
    }
}
