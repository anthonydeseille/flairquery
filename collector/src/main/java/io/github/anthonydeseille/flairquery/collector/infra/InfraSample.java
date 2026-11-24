package io.github.anthonydeseille.flairquery.collector.infra;

import java.util.Map;

/**
 * One infrastructure reading taken during a collection cycle, stored as is (JSON) in fq-store.
 *
 * @param kind    {@link #CONTAINER}, {@link #JVM} or {@link #POSTGRES}
 * @param subject what was read: "namespace/pod/container", "namespace/pod" (JVM of a pod) or the agent URL, the
 *                target name for PostgreSQL
 */
public record InfraSample(String kind, String subject, Map<String, Object> payload) {

    public static final String CONTAINER = "container";
    public static final String JVM = "jvm";
    public static final String POSTGRES = "postgres";
}
