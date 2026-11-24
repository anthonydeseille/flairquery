package io.github.anthonydeseille.flairquery.collector;

import io.github.anthonydeseille.flairquery.collector.config.CollectorConfig;
import io.github.anthonydeseille.flairquery.collector.config.Target;
import io.github.anthonydeseille.flairquery.collector.infra.InfraCollector;
import io.github.anthonydeseille.flairquery.collector.infra.KubeClient;
import io.github.anthonydeseille.flairquery.collector.infra.KubernetesSource;
import io.github.anthonydeseille.flairquery.collector.source.SqlLogPlanSource;
import io.github.anthonydeseille.flairquery.collector.http.CollectorHttpServer;
import io.github.anthonydeseille.flairquery.collector.log.Log;
import io.github.anthonydeseille.flairquery.collector.source.PostgresSource;
import io.github.anthonydeseille.flairquery.collector.store.Store;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Entry point: migrate fq-store, start the HTTP API, collect every FQ_COLLECTOR_INTERVAL. */
public final class CollectorMain {

    private CollectorMain() { }

    public static void main(String[] args) throws Exception {
        CollectorConfig config = CollectorConfig.fromEnv(System.getenv());
        Log.setDebug(config.debug());
        Store store = new Store(config.storeUrl(), config.storeUser(), config.storePassword());
        migrateWithRetry(store);

        // phase 2: single target (the configuration model already holds a list)
        Target target = config.targets().get(0);
        Collector collector = new Collector(new PostgresSource(target),
                config.readPlans() ? new SqlLogPlanSource(target, config.logMaxBytesPerCycle()) : null,
                config.agentUrl() == null ? null
                        : new io.github.anthonydeseille.flairquery.collector.source.AgentSource(config.agentUrl()),
                store, config.retention(), config.columnStatsInterval(), Clock.systemUTC());
        collector.withInfra(infra(config, target));
        CollectorHttpServer http = new CollectorHttpServer(collector, store);
        int port = http.start(config.httpPort());

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "fq-collector"));
        scheduler.scheduleWithFixedDelay(collector::runSafely, 0, config.interval().toMillis(), TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdownNow();
            http.stop();
        }, "fq-collector-shutdown"));

        Log.info("collector started", "target", config.targets().get(0).toString(), "intervalSeconds",
                config.interval().toSeconds(), "retentionHours", config.retention().toHours(), "httpPort", port, "plans", config.readPlans(),
                "columnStatsMinutes", config.columnStatsInterval().toMinutes(),
                "agent", config.agentUrl() == null ? "none" : config.agentUrl());
    }

    /** Phase 6: PostgreSQL settings always; Kubernetes pods, cAdvisor and every agent replica when in a cluster. */
    static InfraCollector infra(CollectorConfig config, Target target) {
        KubernetesSource k8s = null;
        CollectorConfig.Kubernetes kc = config.kubernetes();
        if (kc.enabled(KubeClient.inClusterAvailable(System.getenv()))) {
            try {
                String ns = kc.namespace() != null ? kc.namespace() : KubeClient.ownNamespace();
                if (ns == null) throw new IllegalStateException("namespace unknown: set FQ_COLLECTOR_K8S_NAMESPACE");
                k8s = new KubernetesSource(KubeClient.inCluster(System.getenv()), ns, kc.selector(), kc.cadvisor(),
                        kc.agentPortName());
                Log.info("kubernetes reading enabled", "namespace", ns, "selector", String.valueOf(kc.selector()),
                        "cadvisor", kc.cadvisor(), "agentPort", kc.agentPortName());
            } catch (Exception e) {
                Log.warn("kubernetes reading disabled (pod resources and usage unavailable)", e);
            }
        }
        return new InfraCollector(new PostgresSource(target), k8s, config.agentUrl());
    }

    private static void migrateWithRetry(Store store) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                store.migrate();
                return;
            } catch (Exception e) {
                if (attempt >= 30) throw e;
                Log.warn("fq-store not ready, retrying", e, "attempt", attempt);
                Thread.sleep(2000);
            }
        }
    }
}
