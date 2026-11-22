package io.github.anthonydeseille.flairquery.agent.servlet;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Reads {@code HttpServletRequest} values by reflection, so the agent has no servlet nor Spring
 * dependency. Handles are resolved on the public servlet interfaces (the concrete request class may
 * be non-public) and cached per request class.
 */
public final class ServletRequestAccessor {

    /** {@code org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE}. */
    public static final String BEST_MATCHING_PATTERN =
            "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";

    private static final ClassValue<Handles> HANDLES = new ClassValue<>() {
        @Override
        protected Handles computeValue(Class<?> type) {
            return Handles.resolve(type);
        }
    };

    private ServletRequestAccessor() { }

    public static String method(Object request) {
        return invokeString(HANDLES.get(request.getClass()).getMethod, request);
    }

    public static String uri(Object request) {
        return invokeString(HANDLES.get(request.getClass()).getRequestUri, request);
    }

    public static String bestMatchingPattern(Object request) {
        MethodHandle h = HANDLES.get(request.getClass()).getAttribute;
        if (h == null) {
            return null;
        }
        try {
            Object v = h.invoke(request, BEST_MATCHING_PATTERN);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String invokeString(MethodHandle h, Object target) {
        if (h == null) {
            return null;
        }
        try {
            Object v = h.invoke(target);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private record Handles(MethodHandle getMethod, MethodHandle getRequestUri, MethodHandle getAttribute) {

        static Handles resolve(Class<?> type) {
            ClassLoader cl = type.getClassLoader();
            Class<?> servletRequest = load("jakarta.servlet.ServletRequest", cl);
            Class<?> httpRequest = load("jakarta.servlet.http.HttpServletRequest", cl);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            return new Handles(
                    find(lookup, httpRequest, "getMethod", MethodType.methodType(String.class)),
                    find(lookup, httpRequest, "getRequestURI", MethodType.methodType(String.class)),
                    find(lookup, servletRequest, "getAttribute", MethodType.methodType(Object.class, String.class)));
        }

        private static Class<?> load(String name, ClassLoader cl) {
            try {
                return Class.forName(name, false, cl);
            } catch (Throwable t) {
                return null;
            }
        }

        private static MethodHandle find(MethodHandles.Lookup lookup, Class<?> owner, String name, MethodType type) {
            if (owner == null) {
                return null;
            }
            try {
                return lookup.findVirtual(owner, name, type)
                        .asType(type.insertParameterTypes(0, Object.class));
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
