package io.github.anthonydeseille.flairquery.analyzer.size;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Moves the rendered manifests of an overlay into the throwaway namespace of a replay (phase 6, step 3), without
 * touching what is being sized: the Namespace objects are dropped (the prover creates its own), every namespaced
 * object goes to the replay namespace, cluster roles and their bindings get a per-namespace name (so that the replay
 * never takes over, nor deletes, those of another namespace) and bind the replay's service accounts, and the
 * collector cycles every {@code collectorInterval} seconds (enough CPU rate samples in a short phase). The
 * PersistentVolumeClaims stay: the data must survive the cold restarts of every phase (an emptyDir would not), and
 * they are deleted with the namespace. Pure.
 */
final class Relocation {

    static final Set<String> CLUSTER_SCOPED = Set.of("Namespace", "ClusterRole", "ClusterRoleBinding",
            "CustomResourceDefinition", "PriorityClass", "StorageClass", "PersistentVolume");
    static final String COLLECTOR = "fq-collector";
    /** On every replay object, cluster-scoped ones included (deleted by this label at the end). */
    static final String REPLAY_LABEL = "flairquery.io/replay";

    private Relocation() { }

    static List<ObjectNode> relocate(JsonNode items, String namespace, int collectorInterval) {
        List<ObjectNode> out = new ArrayList<>();
        Set<String> clusterRoles = new java.util.HashSet<>();
        for (JsonNode i : items) {
            if (i.path("kind").asText().equals("ClusterRole")) clusterRoles.add(i.path("metadata").path("name").asText());
        }
        for (JsonNode i : items) {
            ObjectNode o = i.deepCopy();
            String kind = o.path("kind").asText();
            if (kind.equals("Namespace")) continue;
            ObjectNode meta = (ObjectNode) o.path("metadata");
            if (kind.equals("ClusterRole")) {
                meta.put("name", meta.path("name").asText() + "-" + namespace);
            } else if (kind.equals("ClusterRoleBinding")) {
                meta.put("name", meta.path("name").asText() + "-" + namespace);
                JsonNode ref = o.path("roleRef");
                if (ref.path("kind").asText().equals("ClusterRole") && clusterRoles.contains(ref.path("name").asText())) {
                    ((ObjectNode) ref).put("name", ref.path("name").asText() + "-" + namespace);
                }
                for (JsonNode s : o.path("subjects")) {
                    if (s.path("kind").asText().equals("ServiceAccount")) ((ObjectNode) s).put("namespace", namespace);
                }
            } else if (kind.equals("RoleBinding")) {
                for (JsonNode s : o.path("subjects")) {
                    if (s.path("kind").asText().equals("ServiceAccount")) ((ObjectNode) s).put("namespace", namespace);
                }
            }
            if (!CLUSTER_SCOPED.contains(kind)) meta.put("namespace", namespace);
            JsonNode labels = meta.path("labels");
            (labels.isObject() ? (ObjectNode) labels : meta.putObject("labels")).put(REPLAY_LABEL, "true");
            if (kind.equals("Deployment") || kind.equals("StatefulSet")) {
                JsonNode podSpec = o.path("spec").path("template").path("spec");
                if (o.path("metadata").path("name").asText().equals(COLLECTOR)) {
                    for (JsonNode c : podSpec.path("containers")) {
                        for (JsonNode e : c.path("env")) {
                            if (e.path("name").asText().equals("FQ_COLLECTOR_INTERVAL")) {
                                ((ObjectNode) e).put("value", Integer.toString(collectorInterval));
                            }
                        }
                    }
                }
            }
            out.add(o);
        }
        return out;
    }

    /**
     * The objects printed by {@code kubectl create --dry-run=client -o json}: one JSON document per object, one after
     * the other (or a single List).
     */
    static com.fasterxml.jackson.databind.node.ArrayNode parseObjects(String json) throws java.io.IOException {
        ArrayNode out = InfraPatches.JSON.createArrayNode();
        try (com.fasterxml.jackson.databind.MappingIterator<JsonNode> it =
                     InfraPatches.JSON.readerFor(JsonNode.class).readValues(json)) {
            while (it.hasNext()) {
                JsonNode n = it.next();
                if (n.path("kind").asText().equals("List")) n.path("items").forEach(out::add);
                else out.add(n);
            }
        }
        return out;
    }

    /** Rendered Deployments by name. */
    static Map<String, JsonNode> deployments(List<? extends JsonNode> items) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (JsonNode i : items) {
            if (i.path("kind").asText().equals("Deployment")) out.put(i.path("metadata").path("name").asText(), i);
        }
        return out;
    }

    /** {@code kubectl apply} input: a v1 List. */
    static ObjectNode list(List<ObjectNode> items) {
        ObjectNode l = InfraPatches.JSON.createObjectNode();
        l.put("apiVersion", "v1").put("kind", "List");
        ArrayNode a = l.putArray("items");
        items.forEach(a::add);
        return l;
    }
}
