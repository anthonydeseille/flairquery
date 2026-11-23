package io.github.anthonydeseille.flairquery.agent.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Entity models registered while the entity classes are transformed. */
public final class EntityModels {

    static final int MAX_MODELS = 2_000;

    private static final Map<String, EntityModel> BY_NAME = new ConcurrentHashMap<>();

    private static final ClassValue<EntityModel> BY_CLASS = new ClassValue<>() {
        @Override
        protected EntityModel computeValue(Class<?> type) {
            // the entity itself, or its closest mapped parent (getter declared in a @MappedSuperclass)
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                EntityModel m = BY_NAME.get(c.getName());
                if (m != null) return m;
            }
            return null;
        }
    };

    private EntityModels() { }

    public static void register(EntityModel model) {
        if (BY_NAME.size() < MAX_MODELS) {
            BY_NAME.put(model.className(), model);
        }
    }

    public static EntityModel of(String className) {
        return BY_NAME.get(className);
    }

    /** Model of an entity instance's class (cached per class). Never registered after first use: models are
     *  registered when the class is transformed, before any instance exists. */
    public static EntityModel of(Class<?> type) {
        return BY_CLASS.get(type);
    }

    public static List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        BY_NAME.values().stream().sorted(Comparator.comparing(EntityModel::className)).forEach(m -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("entity", m.className());
            e.put("propertyAccess", m.propertyAccess());
            List<Map<String, Object>> attrs = new ArrayList<>();
            for (EntityModel.Attribute a : m.attributes()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("name", a.name());
                x.put("kind", a.kind().name());
                x.put("type", a.javaType());
                if (a.fetch() != null) {
                    x.put("fetch", a.fetch());
                    x.put("fetchExplicit", a.fetchExplicit());
                }
                if (a.large()) x.put("large", true);
                attrs.add(x);
            }
            e.put("attributes", attrs);
            out.add(e);
        });
        return out;
    }

    /** Tests only. */
    public static void clear() {
        BY_NAME.clear();
    }
}
