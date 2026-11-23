package io.github.anthonydeseille.flairquery.collector.compare;

import io.github.anthonydeseille.flairquery.collector.store.Store.Mark;
import io.github.anthonydeseille.flairquery.collector.store.Store.Period;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves a period expression of the compare endpoint:
 * <ul>
 *   <li>{@code <mark>}: from that mark to the next mark (or now)</li>
 *   <li>{@code <bound>..<bound>}: explicit range; a bound is a mark name, an ISO-8601 instant or {@code now}</li>
 * </ul>
 */
public final class Periods {

    /** Allowed mark names (also used to validate POST /fq/collector/marks). */
    public static final Pattern MARK_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private Periods() { }

    public static Period resolve(String expr, String label, List<Mark> marks, Instant now) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException(label + " is required: a mark name or <from>..<to>");
        }
        int sep = expr.indexOf("..");
        if (sep < 0) {
            Mark m = mark(expr, marks);
            Instant end = marks.stream().map(Mark::takenAt).filter(t -> t.isAfter(m.takenAt()))
                    .findFirst().orElse(now);
            return new Period(expr, m.takenAt(), end);
        }
        Instant start = bound(expr.substring(0, sep), marks, now);
        Instant end = bound(expr.substring(sep + 2), marks, now);
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException(label + ": the end must be after the start");
        }
        return new Period(expr, start, end);
    }

    private static Instant bound(String s, List<Mark> marks, Instant now) {
        if (s.equals("now")) return now;
        if (!s.isEmpty() && Character.isDigit(s.charAt(0)) && s.contains("T")) {
            try {
                return Instant.parse(s);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("invalid instant: " + s + " (e.g. 2026-11-23T14:00:00Z)");
            }
        }
        return mark(s, marks).takenAt();
    }

    private static Mark mark(String name, List<Mark> marks) {
        return marks.stream().filter(m -> m.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown mark: " + name));
    }
}
