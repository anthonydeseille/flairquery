package io.github.anthonydeseille.flairquery.agent.model;

import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.annotation.AnnotationList;
import net.bytebuddy.description.enumeration.EnumerationDescription;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds an {@link EntityModel} from a type description (class file annotations, as seen by the agent before
 * the class is loaded). Annotations are matched by name: the agent does not depend on JPA. Covers field
 * access, {@code @MappedSuperclass} parents, the JPA default fetch types and the usual "large column" hints.
 */
public final class EntityModelReader {

    static final String JPA = "jakarta.persistence.";

    private EntityModelReader() { }

    public static boolean isEntity(TypeDescription type) {
        return has(type.getDeclaredAnnotations(), "Entity");
    }

    public static EntityModel read(TypeDescription type) {
        List<EntityModel.Attribute> attributes = new ArrayList<>();
        boolean propertyAccess = false;
        // parents first (@MappedSuperclass), then the entity itself
        List<TypeDescription> chain = new ArrayList<>();
        for (TypeDefinition t = type; t != null && !t.represents(Object.class); t = t.getSuperClass()) {
            TypeDescription d = t.asErasure();
            if (d == type || has(d.getDeclaredAnnotations(), "MappedSuperclass") || has(d.getDeclaredAnnotations(), "Entity")) {
                chain.add(0, d);
            }
        }
        for (TypeDescription d : chain) {
            for (MethodDescription.InDefinedShape m : d.getDeclaredMethods()) {
                if (has(m.getDeclaredAnnotations(), "Id") || has(m.getDeclaredAnnotations(), "EmbeddedId")) {
                    propertyAccess = true;
                }
            }
            for (FieldDescription.InDefinedShape f : d.getDeclaredFields()) {
                if (f.isStatic() || f.isTransient() || f.isSynthetic()) continue;
                AnnotationList a = f.getDeclaredAnnotations();
                if (has(a, "Transient")) continue;
                attributes.add(attribute(attributes.size(), f, a));
            }
        }
        return new EntityModel(type.getName(), List.copyOf(attributes), propertyAccess);
    }

    private static EntityModel.Attribute attribute(int index, FieldDescription f, AnnotationList a) {
        String javaType = f.getType().asErasure().getName();
        EntityModel.Kind kind;
        String fetch = null;
        boolean explicit = false;
        AnnotationDescription assoc = null;
        if (has(a, "Id") || has(a, "EmbeddedId")) {
            kind = EntityModel.Kind.ID;
        } else if ((assoc = get(a, "ManyToOne")) != null || (assoc = get(a, "OneToOne")) != null) {
            kind = EntityModel.Kind.TO_ONE;
            fetch = "EAGER";                                   // JPA default for to-one
        } else if ((assoc = get(a, "OneToMany")) != null || (assoc = get(a, "ManyToMany")) != null
                || (assoc = get(a, "ElementCollection")) != null) {
            kind = EntityModel.Kind.TO_MANY;
            fetch = "LAZY";
        } else if (has(a, "Embedded")) {
            kind = EntityModel.Kind.EMBEDDED;
        } else {
            kind = EntityModel.Kind.BASIC;
            assoc = get(a, "Basic");                           // @Basic(fetch = LAZY) is possible too
        }
        if (assoc != null) {
            // annotation values left out read as their default: "explicit" means "differs from the JPA default"
            // (an explicit fetch = EAGER on a to-one is the same choice as the default, and as costly)
            String declared = fetchOf(assoc);
            String jpaDefault = kind == EntityModel.Kind.TO_MANY ? "LAZY" : "EAGER";
            if (declared != null && !declared.equals(jpaDefault)) {
                fetch = declared;
                explicit = true;
            }
        }
        return new EntityModel.Attribute(index, f.getName(), javaType, kind, kind == EntityModel.Kind.BASIC
                && !explicit ? null : fetch, explicit, large(javaType, a));
    }

    private static String fetchOf(AnnotationDescription assoc) {
        try {
            Object v = assoc.getValue("fetch").resolve();
            return v instanceof EnumerationDescription e ? e.getValue() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Only explicitly declared values count: an annotation value left to its default is not "declared". */
    private static boolean declared(AnnotationDescription d, String property) {
        try {
            return d.getValue(property) != null && !d.getAnnotationType().getDeclaredMethods()
                    .filter(net.bytebuddy.matcher.ElementMatchers.named(property)).getOnly()
                    .getDefaultValue().equals(d.getValue(property));
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean large(String javaType, AnnotationList a) {
        if (has(a, "Lob") || javaType.equals("byte[]")) return true;
        AnnotationDescription column = get(a, "Column");
        if (column == null) return false;
        try {
            String def = String.valueOf(column.getValue("columnDefinition").resolve()).toLowerCase(Locale.ROOT);
            if (def.contains("text") || def.contains("clob") || def.contains("blob") || def.contains("bytea")
                    || def.contains("json")) {
                return true;
            }
            Object length = column.getValue("length").resolve();
            return declared(column, "length") && length instanceof Integer n && n > 2000;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean has(AnnotationList a, String simple) {
        return get(a, simple) != null;
    }

    private static AnnotationDescription get(AnnotationList a, String simple) {
        for (AnnotationDescription d : a) {
            if (d.getAnnotationType().getName().equals(JPA + simple)) return d;
        }
        return null;
    }
}
