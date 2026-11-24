package io.github.anthonydeseille.flairquery.analyzer.prove;

import org.postgresql.PGConnection;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Loads data into the template database once the application has created the schema. */
@FunctionalInterface
interface Seeder {

    void seed(Connection connection) throws Exception;

    Seeder NONE = c -> { };

    /**
     * {@code none}, or {@code corpus:<profile>}: FlairQuery's deterministic corpus ({@code corpus/generate.py},
     * streamed through COPY like {@code corpus/load.sh}), for the demo application's schema.
     */
    static Seeder parse(String spec, Path corpusDir) {
        if (spec == null || spec.equals("none")) return NONE;
        if (spec.startsWith("corpus:")) return new Corpus(corpusDir, spec.substring("corpus:".length()));
        throw new IllegalArgumentException("unknown seed '" + spec + "' (none | corpus:<profile>)");
    }

    /** The demo corpus: publisher, author, book, review, sequences moved past the loaded ids. */
    record Corpus(Path dir, String profile) implements Seeder {

        static final List<String> TABLES = List.of("publisher", "author", "book", "review");

        @Override
        public void seed(Connection c) throws Exception {
            Path script = dir.resolve("generate.py");
            if (!Files.isRegularFile(script)) throw new IllegalStateException("corpus generator not found: " + script);
            requireTables(c, TABLES);
            for (String table : TABLES) {
                String columns = new String(output(script, "--profile", profile, "--columns", table)).trim();
                Process p = python(script, "--profile", profile, "--table", table);
                try (InputStream csv = p.getInputStream()) {
                    c.unwrap(PGConnection.class).getCopyAPI()
                            .copyIn("COPY " + table + " (" + columns + ") FROM STDIN WITH (FORMAT csv)", csv);
                }
                if (!p.waitFor(10, TimeUnit.MINUTES) || p.exitValue() != 0) {
                    throw new IllegalStateException("generate.py failed for " + table);
                }
            }
            try (Statement st = c.createStatement()) {
                for (String table : TABLES) {
                    st.execute("SELECT setval(pg_get_serial_sequence('" + table + "','id'), (SELECT max(id) FROM "
                            + table + "))");
                }
            }
        }

        private static byte[] output(Path script, String... args) throws IOException, InterruptedException {
            Process p = python(script, args);
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(1, TimeUnit.MINUTES) || p.exitValue() != 0) {
                throw new IllegalStateException("generate.py " + String.join(" ", args) + " failed");
            }
            return out;
        }

        private static Process python(Path script, String... args) throws IOException {
            List<String> cmd = new ArrayList<>(List.of("python3", "-I", script.toString()));
            cmd.addAll(List.of(args));
            return new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        }
    }

    /** Fails fast on an empty schema (the application did not run its migrations). */
    static void requireTables(Connection c, List<String> tables) throws SQLException {
        try (Statement st = c.createStatement(); var rs = st.executeQuery(
                "SELECT count(*) FROM information_schema.tables WHERE table_name IN ('" + String.join("','", tables) + "')")) {
            rs.next();
            if (rs.getInt(1) != tables.size()) throw new IllegalStateException("schema not created by the application");
        }
    }
}
