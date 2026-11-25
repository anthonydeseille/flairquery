package io.github.anthonydeseille.flairquery.analyzer.size;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a sizing finding into a Kustomize strategic-merge patch on the workload's Deployment (phase 6). The patch is
 * computed against the rendered manifests (the overlay being sized), so that it keeps what it does not change: the
 * other JVM options of the variable carrying {@code -XX:MaxRAMPercentage}, the other PostgreSQL arguments.
 *
 * <ul>
 *   <li>JVM_HEAP_TOO_LARGE: {@code -XX:MaxRAMPercentage} in the environment variable that sets it (else appended to
 *       {@code JAVA_TOOL_OPTIONS}), plus the memory limit when the finding raises it;</li>
 *   <li>REQUESTS_BELOW_USAGE / CPU_THROTTLED: {@code resources.requests} / {@code resources.limits.cpu};</li>
 *   <li>WORK_MEM_SPILLS / SHARED_BUFFERS_SMALL: {@code -c name=value} PostgreSQL arguments (they override
 *       postgresql.conf); the per-role SQL of the finding remains the alternative without a restart;</li>
 *   <li>POOL_EXCEEDS_CONNECTIONS: the environment variable holding the pool size on the pooled workload.</li>
 * </ul>
 * Pure: unit-tested on manifests, no cluster.
 */
public final class InfraPatches {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String SPRING_POOL_ENV = "SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE";

    /** A patch that cannot be generated (unsupported workload kind, ambiguous container, value set from a secret). */
    public static final class Unsupported extends Exception {
        Unsupported(String message) {
            super(message);
        }
    }

    /**
     * @param findingId the finding it answers
     * @param kind      patched object kind (Deployment)
     * @param name      patched object name
     * @param fileName  file name of the patch in the overlay
     * @param yaml      the strategic-merge patch
     * @param summary   what it changes, one line ("demo-app: MaxRAMPercentage 80.0 -> 60")
     */
    public record Patch(String findingId, String rule, String kind, String name, String fileName, String yaml,
                        String summary) { }

    private InfraPatches() { }

    /**
     * @param finding     the sizing finding
     * @param deployments rendered Deployments of the overlay, by name
     */
    public static Patch of(SizingFinding finding, Map<String, JsonNode> deployments) throws Unsupported {
        JsonNode f = JSON.valueToTree(finding);
        JsonNode rec = f.path("recommended");
        Sizer.Rule rule = Sizer.Rule.valueOf(finding.rule());
        return switch (rule) {
            case JVM_HEAP_TOO_LARGE -> heap(finding, rec, deployments);
            case REQUESTS_BELOW_USAGE -> resources(finding, deployments, "requests",
                    entries(rec, "cpuRequest", "cpu", "memoryRequest", "memory"));
            case CPU_THROTTLED -> resources(finding, deployments, "limits", entries(rec, "cpuLimit", "cpu"));
            case WORK_MEM_SPILLS -> postgresArgs(finding, deployments, entries(rec, "work_mem", "work_mem"));
            case SHARED_BUFFERS_SMALL -> postgresArgs(finding, deployments,
                    entries(rec, "shared_buffers", "shared_buffers", "effective_cache_size", "effective_cache_size"));
            case POOL_EXCEEDS_CONNECTIONS -> pool(finding, f, rec, deployments);
        };
    }

    // ---------------------------------------------------------------- per rule

    private static Patch heap(SizingFinding finding, JsonNode rec, Map<String, JsonNode> deployments) throws Unsupported {
        Target t = containerTarget(finding.target(), deployments);
        String pct = rec.path("MaxRAMPercentage").asText();
        if (pct.isEmpty()) throw new Unsupported("no MaxRAMPercentage recommended");
        String flag = "-XX:MaxRAMPercentage=" + pct;

        JsonNode env = null;
        for (JsonNode e : t.container.path("env")) {
            if (e.path("value").asText("").contains("-XX:MaxRAMPercentage=")) env = e;
        }
        String name;
        String value;
        if (env != null) {
            name = env.path("name").asText();
            value = env.path("value").asText().replaceAll("-XX:MaxRAMPercentage=[0-9.]+", flag);
        } else {
            name = "JAVA_TOOL_OPTIONS";
            JsonNode jto = envVar(t.container, name);
            if (jto != null && !jto.has("value")) throw new Unsupported("JAVA_TOOL_OPTIONS is set from a reference (valueFrom)");
            value = jto == null || jto.path("value").asText().isBlank() ? flag : jto.path("value").asText() + " " + flag;
        }
        Map<String, Object> c = container(t.containerName);
        c.put("env", List.of(nameValue(name, value)));
        String summary = t.name + ": " + name + " " + flag;
        String limit = rec.path("memoryLimit").asText(null);
        String current = finding.current() == null ? null : String.valueOf(finding.current().get("memoryLimit"));
        if (limit != null && !limit.equals(current)) {
            c.put("resources", Map.of("limits", Map.of("memory", limit)));
            summary += ", memory limit " + current + " -> " + limit;
        }
        return patch(finding, t, c, summary);
    }

    private static Patch resources(SizingFinding finding, Map<String, JsonNode> deployments, String section,
                                   Map<String, String> values) throws Unsupported {
        if (values.isEmpty()) throw new Unsupported("nothing recommended");
        Target t = containerTarget(finding.target(), deployments);
        Map<String, Object> c = container(t.containerName);
        c.put("resources", Map.of(section, new LinkedHashMap<>(values)));
        List<String> parts = new ArrayList<>();
        JsonNode cur = t.container.path("resources").path(section);
        values.forEach((k, v) -> parts.add(k + " " + cur.path(k).asText("none") + " -> " + v));
        return patch(finding, t, c, t.name + "/" + t.containerName + ": " + section + " " + String.join(", ", parts));
    }

    private static Patch postgresArgs(SizingFinding finding, Map<String, JsonNode> deployments,
                                      Map<String, String> settings) throws Unsupported {
        if (settings.isEmpty()) throw new Unsupported("no setting recommended");
        String workload = finding.target().startsWith("postgres:") ? finding.target().substring("postgres:".length())
                : finding.target();
        Target t = workloadTarget(workload, deployments, null, false);
        if (t.container.has("command") && !t.container.has("args")) {
            throw new Unsupported("the PostgreSQL container sets a command without args: arguments not patched");
        }
        List<String> args = new ArrayList<>();
        t.container.path("args").forEach(a -> args.add(a.asText()));
        List<String> parts = new ArrayList<>();
        settings.forEach((name, value) -> {
            boolean replaced = false;
            for (int i = 0; i + 1 < args.size(); i++) {
                if (args.get(i).equals("-c") && args.get(i + 1).startsWith(name + "=")) {
                    parts.add(args.get(i + 1) + " -> " + name + "=" + value);
                    args.set(i + 1, name + "=" + value);
                    replaced = true;
                }
            }
            if (!replaced) {
                args.add("-c");
                args.add(name + "=" + value);
                parts.add(name + "=" + value);
            }
        });
        Map<String, Object> c = container(t.containerName);
        c.put("args", args);   // a list of strings: replaced as a whole by a strategic merge
        return patch(finding, t, c, t.name + ": PostgreSQL " + String.join(", ", parts));
    }

    private static Patch pool(SizingFinding finding, JsonNode f, JsonNode rec, Map<String, JsonNode> deployments)
            throws Unsupported {
        JsonNode pools = f.path("evidence").path("pools");
        if (pools.size() != 1) throw new Unsupported(pools.size() + " pooled workloads: one patch per workload not supported yet");
        String workload = pools.get(0).path("workload").asText();
        String current = pools.get(0).path("maximumPoolSize").asText();
        String size = rec.path("maximumPoolSize").asText();
        if (size.isEmpty()) throw new Unsupported("no pool size recommended");

        // the variable that carries the pool size: Spring's own, else one named *POOL* holding the current size
        Target t = workloadTarget(workload, deployments, c -> poolEnv(c, current) != null, false);
        JsonNode env = poolEnv(t.container, current);
        String name = env == null ? SPRING_POOL_ENV : env.path("name").asText();
        Map<String, Object> c = container(t.containerName);
        c.put("env", List.of(nameValue(name, size)));
        return patch(finding, t, c, t.name + ": " + name + " " + current + " -> " + size + " (connections per replica)");
    }

    static JsonNode poolEnv(JsonNode container, String currentSize) {
        for (String n : List.of(SPRING_POOL_ENV, "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE")) {
            JsonNode e = envVar(container, n);
            if (e != null && e.has("value")) return e;
        }
        for (JsonNode e : container.path("env")) {
            if (e.path("name").asText().toUpperCase(java.util.Locale.ROOT).contains("POOL")
                    && e.path("value").asText("").equals(currentSize)) {
                return e;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private record Target(String kind, String name, String containerName, JsonNode container) { }

    /** "Deployment/demo-app/demo-app": workload kind/name, then container. */
    private static Target containerTarget(String target, Map<String, JsonNode> deployments) throws Unsupported {
        int last = target.lastIndexOf('/');
        if (last <= 0 || target.indexOf('/') == last) throw new Unsupported("not a container target: " + target);
        String workload = target.substring(0, last);
        String container = target.substring(last + 1);
        return workloadTarget(workload, deployments, c -> c.path("name").asText().equals(container), true);
    }

    /**
     * @param pick         chooses the container when there are several
     * @param pickRequired the single container must match {@code pick} too
     */
    private static Target workloadTarget(String workload, Map<String, JsonNode> deployments,
                                         java.util.function.Predicate<JsonNode> pick, boolean pickRequired)
            throws Unsupported {
        int slash = workload.indexOf('/');
        String kind = slash < 0 ? "" : workload.substring(0, slash);
        String name = workload.substring(slash + 1);
        if (!kind.equals("Deployment")) throw new Unsupported(kind + " " + name + ": only Deployments are patched");
        JsonNode d = deployments.get(name);
        if (d == null) throw new Unsupported("Deployment " + name + " not found in the manifests");
        JsonNode containers = d.path("spec").path("template").path("spec").path("containers");
        JsonNode found = null;
        if (containers.size() == 1) found = containers.get(0);
        else if (pick != null) {
            for (JsonNode c : containers) {
                if (pick.test(c)) {
                    if (found != null) throw new Unsupported("Deployment " + name + ": several matching containers");
                    found = c;
                }
            }
        }
        if (found == null || (pickRequired && !pick.test(found))) {
            throw new Unsupported("Deployment " + name + ": container not identified");
        }
        return new Target(kind, name, found.path("name").asText(), found);
    }

    private static JsonNode envVar(JsonNode container, String name) {
        for (JsonNode e : container.path("env")) {
            if (e.path("name").asText().equals(name)) return e;
        }
        return null;
    }

    private static Map<String, Object> nameValue(String name, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        return m;
    }

    private static Map<String, Object> container(String name) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        return c;
    }

    /** Pairs (finding key, manifest key) of the recommended values present. */
    private static Map<String, String> entries(JsonNode rec, String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (rec.hasNonNull(pairs[i])) out.put(pairs[i + 1], rec.get(pairs[i]).asText());
        }
        return out;
    }

    private static Patch patch(SizingFinding finding, Target t, Map<String, Object> container, String summary) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("apiVersion", "apps/v1");
        doc.put("kind", t.kind);
        doc.put("metadata", Map.of("name", t.name));
        doc.put("spec", Map.of("template", Map.of("spec", Map.of("containers", List.of(container)))));
        String header = "# FlairQuery phase 6: " + finding.rule() + " (" + finding.id() + ")\n# " + summary + "\n";
        return new Patch(finding.id(), finding.rule(), t.kind, t.name, finding.id() + ".yaml",
                header + Yaml.write(doc), summary);
    }

    /** Kustomization of an overlay applying {@code patches} on top of {@code base} (relative path). */
    public static String kustomization(String base, List<Patch> patches) {
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("apiVersion", "kustomize.config.k8s.io/v1beta1");
        k.put("kind", "Kustomization");
        k.put("resources", List.of(base));
        List<Object> ps = new ArrayList<>();
        for (Patch p : patches) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("path", p.fileName());
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("kind", p.kind());
            target.put("name", p.name());
            e.put("target", target);
            ps.add(e);
        }
        k.put("patches", ps);
        return "# generated by FlairQuery (phase 6): sizing patches on top of " + base + "\n" + Yaml.write(k);
    }
}
