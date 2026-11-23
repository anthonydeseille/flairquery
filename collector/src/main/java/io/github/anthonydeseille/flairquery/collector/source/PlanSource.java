package io.github.anthonydeseille.flairquery.collector.source;

import java.util.List;
import java.util.Map;

/**
 * Where auto_explain plans come from. Phase 2: the JSON server log read through SQL (self-hosted / K8s).
 * Managed databases (RDS, Cloud SQL...) will need another implementation (provider log APIs).
 */
public interface PlanSource {

    record Batch(List<LogEntry> entries, Map<String, LogCursor> cursors, long lagBytes, int unparsable) { }

    /**
     * Reads what was appended since the given cursors. On the first call ({@code startAtEnd}) existing
     * content is skipped, consistently with the statistics baseline.
     */
    Batch read(Map<String, LogCursor> cursors, boolean startAtEnd) throws Exception;
}
