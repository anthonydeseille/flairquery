package io.github.anthonydeseille.flairquery.agent.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mapping of one {@code @Entity} class, read from its bytecode (annotations only, the class is never
 * initialized): persistent attributes, their kind, fetch type (explicit or JPA default) and size hint.
 */
public final class EntityModel {

    public enum Kind { ID, BASIC, EMBEDDED, TO_ONE, TO_MANY }

    /**
     * @param fetchExplicit the fetch type differs from the JPA default (EAGER for to-one, LAZY for to-many);
     *                      false for a to-one left EAGER, the cause of trap 2
     * @param large         TEXT / CLOB / LOB / long VARCHAR / byte[]: expensive to load for nothing
     */
    public record Attribute(int index, String name, String javaType, Kind kind, String fetch, boolean fetchExplicit,
                            boolean large) {

        public boolean association() {
            return kind == Kind.TO_ONE || kind == Kind.TO_MANY;
        }
    }

    private final String className;
    private final List<Attribute> attributes;
    private final boolean propertyAccess;
    /** getter name ({@code getTitle}, {@code isActive}) -> attribute index. */
    private final Map<String, Integer> getters = new HashMap<>();

    /**
     * @param propertyAccess {@code @Id} is on a getter: Hibernate itself calls the getters (dirty checking), so
     *                       getter calls do not mean "read by the application" and are not tracked
     */
    public EntityModel(String className, List<Attribute> attributes, boolean propertyAccess) {
        this.className = className;
        this.attributes = List.copyOf(attributes);
        this.propertyAccess = propertyAccess;
        for (Attribute a : this.attributes) {
            String cap = Character.toUpperCase(a.name().charAt(0)) + a.name().substring(1);
            getters.put("get" + cap, a.index());
            if (a.javaType().equals("boolean")) {
                getters.put("is" + cap, a.index());
            }
        }
    }

    public String className() { return className; }
    public List<Attribute> attributes() { return attributes; }
    public boolean propertyAccess() { return propertyAccess; }

    /** Attribute index of a getter, or -1 (not a persistent attribute's getter). */
    public int attributeOfGetter(String method) {
        Integer i = getters.get(method);
        return i == null ? -1 : i;
    }

    public String simpleName() {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
