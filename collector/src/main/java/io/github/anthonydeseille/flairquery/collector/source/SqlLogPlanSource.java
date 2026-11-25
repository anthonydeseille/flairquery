package io.github.anthonydeseille.flairquery.collector.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.anthonydeseille.flairquery.collector.config.Target;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Reads the JSON server log (log_destination = jsonlog) through SQL, with the read-only role:
 * {@code pg_ls_logdir()} to list files, {@code pg_read_binary_file(file, offset, length)} to read what was
 * appended. Only complete lines are consumed; a bounded number of bytes is read per cycle, in chunks of
 * {@link #CHUNK_BYTES}.
 */
public final class SqlLogPlanSource implements PlanSource {

    static final int HEAD_BYTES = 48;
    /** Bytes read from the log per query: bounds the memory of a read, whatever the backlog. */
    static final int CHUNK_BYTES = 4 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter PG_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Target target;
    private final long maxBytesPerCycle;
    private final int chunkBytes;

    public SqlLogPlanSource(Target target, long maxBytesPerCycle) {
        this(target, maxBytesPerCycle, CHUNK_BYTES);
    }

    /** {@code chunkBytes}: bytes per read (tests use tiny chunks to cross lines and files). */
    public SqlLogPlanSource(Target target, long maxBytesPerCycle, int chunkBytes) {
        this.target = target;
        this.maxBytesPerCycle = maxBytesPerCycle;
        this.chunkBytes = chunkBytes;
    }

    @Override
    public Batch read(Map<String, LogCursor> cursors, boolean startAtEnd) throws SQLException {
        Map<String, LogCursor> next = new HashMap<>(cursors);
        List<LogEntry> entries = new ArrayList<>();
        long budget = maxBytesPerCycle;
        long lag = 0;
        int unparsable = 0;
        try (Connection c = connect()) {
            List<String[]> files = new ArrayList<>(); // name, size
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT name, size FROM pg_ls_logdir() WHERE name LIKE '%.json' ORDER BY modification")) {
                while (rs.next()) files.add(new String[]{rs.getString(1), Long.toString(rs.getLong(2))});
            }
            for (String[] f : files) {
                String name = f[0];
                long size = Long.parseLong(f[1]);
                String head = size == 0 ? "" : readHead(c, name, size);
                LogCursor cursor = next.get(name);
                long offset;
                if (cursor == null) {
                    offset = startAtEnd ? size : 0;            // new file: from its start (or skip on baseline)
                } else if (!sameContent(cursor.head(), head) || size < cursor.offset()) {
                    offset = startAtEnd ? size : 0;            // file reused / truncated: new content
                } else {
                    offset = cursor.offset();
                }
                // read in chunks: memory stays bounded by the chunk, never by the backlog (a restart after a burst
                // of traffic finds tens of MB of log to read; reading it at once got the collector OOMKilled)
                int chunkSize = chunkBytes;
                while (size - offset > 0 && budget > 0) {
                    long length = Math.min(Math.min(size - offset, budget), chunkSize);
                    byte[] chunk = readBytes(c, name, offset, length);
                    int end = lastNewline(chunk);
                    if (end < 0) {
                        // a line longer than the chunk: read more at once, within the budget
                        if (length < Math.min(size - offset, budget)) {
                            chunkSize = (int) Math.min((long) chunkSize * 2, Integer.MAX_VALUE / 2);
                            continue;
                        }
                        break;   // incomplete last line: read on a later cycle
                    }
                    String text = new String(chunk, 0, end + 1, StandardCharsets.UTF_8);
                    for (String line : text.split("\n")) {
                        if (line.isEmpty()) continue;
                        try {
                            LogEntry e = parse(line);
                            if (e != null) entries.add(e);
                        } catch (Exception ex) {
                            unparsable++;
                        }
                    }
                    offset += end + 1;
                    budget -= end + 1;
                    chunkSize = chunkBytes;
                }
                lag += size - offset;
                next.put(name, new LogCursor(name, offset, head));
            }
        }
        return new Batch(entries, next, lag, unparsable);
    }

    /** auto_explain entries only ("duration: ... plan: {json}"), with a query_id. */
    static LogEntry parse(String line) throws Exception {
        if (!line.contains("plan:")) {
            return null;
        }
        JsonNode n = JSON.readTree(line);
        String message = n.path("message").asText();
        int planAt = message.indexOf("plan:");
        if (!message.startsWith("duration:") || planAt < 0 || !n.has("query_id")) {
            return null;
        }
        double duration = Double.parseDouble(message.substring("duration:".length(), message.indexOf(" ms")).trim());
        JsonNode explain = JSON.readTree(message.substring(message.indexOf('{', planAt)));
        return new LogEntry(timestamp(n.path("timestamp").asText()), n.path("dbname").asText(), n.path("user").asText(),
                n.get("query_id").asLong(), duration, explain.path("Query Text").asText(),
                // values are removed before anything else sees the plan; the query text (with values when
                // literals are inlined) is only used in memory for its tag and its statement, never stored
                io.github.anthonydeseille.flairquery.collector.plan.PlanMasker.mask(explain.path("Plan")));
    }

    static Instant timestamp(String pg) {
        try { // "2026-11-23 10:36:54.361 UTC"
            return LocalDateTime.parse(pg.substring(0, 23), PG_TS).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return Instant.now();
        }
    }

    /** A file shorter than HEAD_BYTES grows: its head gets longer but keeps the same prefix. */
    static boolean sameContent(String stored, String current) {
        if (stored == null) return false;
        return current.startsWith(stored) || stored.startsWith(current);
    }

    static int lastNewline(byte[] b) {
        for (int i = b.length - 1; i >= 0; i--) {
            if (b[i] == '\n') return i;
        }
        return -1;
    }

    private String readHead(Connection c, String name, long size) throws SQLException {
        byte[] b = readBytes(c, name, 0, Math.min(HEAD_BYTES, size));
        return new String(b, StandardCharsets.ISO_8859_1); // byte-exact, no decoding error possible
    }

    private static byte[] readBytes(Connection c, String name, long offset, long length) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_read_binary_file(?, ?, ?)")) {
            ps.setString(1, "log/" + name);
            ps.setLong(2, offset);
            ps.setLong(3, length);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                byte[] b = rs.getBytes(1);
                return b == null ? new byte[0] : b;
            }
        }
    }

    private Connection connect() throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", target.user());
        p.setProperty("password", target.password());
        p.setProperty("ApplicationName", "flairquery-collector");
        p.setProperty("readOnly", "true");
        p.setProperty("connectTimeout", "10");
        p.setProperty("socketTimeout", "120");
        return DriverManager.getConnection(target.jdbcUrl(), p);
    }
}
