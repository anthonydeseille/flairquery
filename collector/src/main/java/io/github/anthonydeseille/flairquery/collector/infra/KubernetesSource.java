package io.github.anthonydeseille.flairquery.collector.infra;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.anthonydeseille.flairquery.collector.log.Log;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads, from the Kubernetes API (read-only), the pods of one namespace (optionally filtered by a label selector):
 * each container's requests and limits, restarts and last termination (OOMKilled), startup time; and, through the
 * API server proxy to each node's kubelet, the cAdvisor usage: CPU time, CFS throttling, working set, RSS.
 * Also lists the FlairQuery agents to read (pods exposing the agent's named port), so every replica is read.
 */
public final class KubernetesSource {

    private final KubeClient client;
    private final String namespace;
    private final String selector;       // null: every pod of the namespace
    private final boolean cadvisor;
    private final String agentPortName;

    public KubernetesSource(KubeClient client, String namespace, String selector, boolean cadvisor, String agentPortName) {
        this.client = client;
        this.namespace = namespace;
        this.selector = selector;
        this.cadvisor = cadvisor;
        this.agentPortName = agentPortName;
    }

    public String namespace() {
        return namespace;
    }

    /** A FlairQuery agent reachable on a pod's IP. */
    public record Agent(String namespace, String pod, String workload, String baseUrl) { }

    public record Reading(List<InfraSample> containers, List<Agent> agents, List<String> warnings) { }

    public Reading read() throws Exception {
        String path = "/api/v1/namespaces/" + enc(namespace) + "/pods"
                + (selector == null ? "" : "?labelSelector=" + enc(selector));
        JsonNode pods = client.getJson(path).path("items");

        List<Map<String, Object>> containers = new ArrayList<>();
        List<Agent> agents = new ArrayList<>();
        Set<String> nodes = new TreeSet<>();
        Set<String> podKeys = new LinkedHashSet<>();
        for (JsonNode pod : pods) {
            parsePod(pod, containers, agents);
            String node = pod.path("spec").path("nodeName").asText("");
            if (!node.isEmpty()) nodes.add(node);
            podKeys.add(pod.path("metadata").path("namespace").asText(namespace) + "/" + pod.path("metadata").path("name").asText());
        }

        List<String> warnings = new ArrayList<>();
        if (cadvisor) {
            Map<Cadvisor.Key, Map<String, Double>> usage = new LinkedHashMap<>();
            for (String node : nodes) {
                try {
                    usage.putAll(Cadvisor.parse(client.get("/api/v1/nodes/" + enc(node) + "/proxy/metrics/cadvisor"), podKeys));
                } catch (Exception e) {
                    warnings.add("cAdvisor not readable on node " + node + ": " + e.getMessage());
                    Log.warn("cAdvisor metrics not readable (usage and throttling unavailable for this node)", e, "node", node);
                }
            }
            for (Map<String, Object> c : containers) {
                Map<String, Double> u = usage.get(new Cadvisor.Key((String) c.get("namespace"), (String) c.get("pod"),
                        (String) c.get("container")));
                if (u != null) c.put("usage", u);
            }
        }
        List<InfraSample> samples = new ArrayList<>();
        for (Map<String, Object> c : containers) {
            samples.add(new InfraSample(InfraSample.CONTAINER, c.get("namespace") + "/" + c.get("pod") + "/" + c.get("container"), c));
        }
        return new Reading(samples, agents, warnings);
    }

    void parsePod(JsonNode pod, List<Map<String, Object>> containers, List<Agent> agents) {
        JsonNode meta = pod.path("metadata");
        JsonNode spec = pod.path("spec");
        JsonNode status = pod.path("status");
        String ns = meta.path("namespace").asText(namespace);
        String name = meta.path("name").asText();
        String workload = workload(meta);
        Instant readyAt = null;
        for (JsonNode cond : status.path("conditions")) {
            if ("Ready".equals(cond.path("type").asText()) && "True".equals(cond.path("status").asText())) {
                readyAt = instant(cond.path("lastTransitionTime"));
            }
        }
        Map<String, JsonNode> statuses = new LinkedHashMap<>();
        for (JsonNode cs : status.path("containerStatuses")) statuses.put(cs.path("name").asText(), cs);

        for (JsonNode c : spec.path("containers")) {
            String cname = c.path("name").asText();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("namespace", ns);
            m.put("pod", name);
            m.put("workload", workload);
            m.put("container", cname);
            m.put("node", spec.path("nodeName").asText(null));
            m.put("phase", status.path("phase").asText(null));
            m.put("qosClass", status.path("qosClass").asText(null));
            JsonNode res = c.path("resources");
            m.put("requests", resources(res.path("requests")));
            m.put("limits", resources(res.path("limits")));
            JsonNode cs = statuses.get(cname);
            if (cs != null) {
                m.put("containerId", cs.path("containerID").asText(null));
                m.put("ready", cs.path("ready").asBoolean(false));
                m.put("restarts", cs.path("restartCount").asInt(0));
                JsonNode running = cs.path("state").path("running");
                Instant startedAt = instant(running.path("startedAt"));
                m.put("state", running.isMissingNode() ? stateName(cs.path("state")) : "running");
                m.put("startedAt", startedAt == null ? null : startedAt.toString());
                // time from the container start to the pod being Ready (probes included): the startup the user sees
                if (startedAt != null && readyAt != null && !readyAt.isBefore(startedAt)) {
                    m.put("startupSeconds", Duration.between(startedAt, readyAt).toMillis() / 1000.0);
                }
                JsonNode term = cs.path("lastState").path("terminated");
                if (!term.isMissingNode()) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("reason", term.path("reason").asText(null));
                    t.put("exitCode", term.path("exitCode").asInt());
                    t.put("finishedAt", term.path("finishedAt").asText(null));
                    m.put("lastTermination", t);
                }
            }
            containers.add(m);

            if (agentPortName != null && "Running".equals(status.path("phase").asText())) {
                String ip = status.path("podIP").asText("");
                for (JsonNode p : c.path("ports")) {
                    if (agentPortName.equals(p.path("name").asText()) && !ip.isEmpty()) {
                        String host = ip.contains(":") ? "[" + ip + "]" : ip;
                        agents.add(new Agent(ns, name, workload, "http://" + host + ":" + p.path("containerPort").asInt()));
                    }
                }
            }
        }
    }

    /** Deployment name for ReplicaSet-owned pods (pod-template-hash removed), otherwise the owner kind/name. */
    static String workload(JsonNode meta) {
        JsonNode owner = meta.path("ownerReferences").path(0);
        if (owner.isMissingNode()) return "Pod/" + meta.path("name").asText();
        String kind = owner.path("kind").asText();
        String name = owner.path("name").asText();
        String hash = meta.path("labels").path("pod-template-hash").asText("");
        if ("ReplicaSet".equals(kind) && !hash.isEmpty() && name.endsWith("-" + hash)) {
            return "Deployment/" + name.substring(0, name.length() - hash.length() - 1);
        }
        return kind + "/" + name;
    }

    private static Map<String, Object> resources(JsonNode r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cpuMillis", r.hasNonNull("cpu") ? Quantity.millicores(r.get("cpu").asText()) : null);
        m.put("memoryBytes", r.hasNonNull("memory") ? Quantity.bytes(r.get("memory").asText()) : null);
        return m;
    }

    private static String stateName(JsonNode state) {
        var it = state.fieldNames();
        if (!it.hasNext()) return null;
        String s = it.next();
        String reason = state.path(s).path("reason").asText("");
        return reason.isEmpty() ? s : s + ":" + reason;
    }

    private static Instant instant(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        try {
            return Instant.parse(n.asText());
        } catch (Exception e) {
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
