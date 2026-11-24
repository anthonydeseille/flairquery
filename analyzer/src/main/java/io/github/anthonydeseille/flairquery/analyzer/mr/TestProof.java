package io.github.anthonydeseille.flairquery.analyzer.mr;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Proves a regression test red / green: it must <em>fail on an assertion</em> on the current code (not on a
 * compilation error, an error at startup or a skip: that would be a false red) and pass with the fix.
 */
public interface TestProof {

    /**
     * @param red     the assertion message on the current code (what the reviewer sees), null when not red
     * @param reasons why it is not red / green, empty when it is
     */
    record Verdict(String red, List<String> reasons) {
        public boolean passed() {
            return reasons.isEmpty();
        }
    }

    Verdict prove(RegressionTest test, Path fixPatch) throws IOException, InterruptedException;

    /** Outcome of one test class run, from its Surefire report. */
    record Run(int tests, int failures, int errors, int skipped, String firstFailure, String firstError) {

        static final Run NOT_RUN = new Run(0, 0, 0, 0, null, null);

        boolean ran() {
            return tests > skipped;
        }

        boolean green() {
            return ran() && failures == 0 && errors == 0;
        }
    }

    /** Reads {@code TEST-<class>.xml}; {@link Run#NOT_RUN} when missing (not compiled, build failed). */
    static Run report(Path surefireReports, String className) throws IOException {
        Path xml = surefireReports.resolve("TEST-" + className + ".xml");
        if (!Files.isRegularFile(xml)) return Run.NOT_RUN;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = f.newDocumentBuilder().parse(xml.toFile());
            Element suite = doc.getDocumentElement();
            return new Run(integer(suite, "tests"), integer(suite, "failures"), integer(suite, "errors"),
                    integer(suite, "skipped"), message(doc.getElementsByTagName("failure")),
                    message(doc.getElementsByTagName("error")));
        } catch (Exception e) {
            throw new IOException("unreadable Surefire report " + xml + ": " + e.getMessage(), e);
        }
    }

    private static int integer(Element e, String attribute) {
        String v = e.getAttribute(attribute);
        return v.isEmpty() ? 0 : Integer.parseInt(v.trim());
    }

    private static String message(NodeList nodes) {
        if (nodes.getLength() == 0) return null;
        Element e = (Element) nodes.item(0);
        String m = e.getAttribute("message");
        if (m.isBlank()) m = e.getAttribute("type");
        m = m.strip().replaceAll("\\s+", " ");
        return m.length() > 400 ? m.substring(0, 400) + "..." : m;
    }

    /** Verdict from the run on the current code and the run with the fix ({@code buildLog} for a run that did not happen). */
    static Verdict judge(Run before, String beforeLog, Run after, String afterLog) {
        List<String> reasons = new ArrayList<>();
        String red = null;
        if (!before.ran()) {
            reasons.add(before.tests() == 0 ? "on the current code it did not run (it must compile against the current "
                    + "code, not only the fixed one): " + beforeLog : "on the current code it was skipped");
        } else if (before.errors() > 0) {
            reasons.add("on the current code it fails with an error, not an assertion: " + before.firstError());
        } else if (before.failures() == 0) {
            reasons.add("it passes on the current code: its assertions do not catch the problem");
        } else {
            red = before.firstFailure();
        }
        if (!after.ran()) {
            reasons.add(after.tests() == 0 ? "with the fix it did not run: " + afterLog : "with the fix it was skipped");
        } else if (!after.green()) {
            reasons.add("it fails with the fix: " + (after.firstFailure() != null ? after.firstFailure() : after.firstError()));
        }
        return new Verdict(red, reasons);
    }

    /**
     * Runs the test with Maven ({@code mvnw test -Dtest=<class>}) on two copies of the application: the current
     * code, and the code with the fix. The application's own test setup is used (Testcontainers needs Docker).
     */
    final class Maven implements TestProof {

        private final Path repo;
        private final String app;
        private final Path work;
        private final List<String> mvn;

        /** @param mvn the Maven command ({@code <repo>/mvnw}), run in the copied application directory */
        public Maven(Path repo, String app, Path work, List<String> mvn) {
            this.repo = repo;
            this.app = app;
            this.work = work;
            this.mvn = List.copyOf(mvn);
        }

        @Override
        public Verdict prove(RegressionTest test, Path fixPatch) throws IOException, InterruptedException {
            String name = test.simpleName();
            Path before = work.resolve(name + "-current");
            Path after = work.resolve(name + "-fixed");
            Run b = run(test, before, null);
            Run a = run(test, after, fixPatch);
            Verdict v = judge(b, tail(before.resolve("test.log")), a, tail(after.resolve("test.log")));
            Tree.delete(before);
            Tree.delete(after);
            return v;
        }

        private Run run(RegressionTest test, Path root, Path patch) throws IOException, InterruptedException {
            Tree.delete(root);
            Path copy = root.resolve(app);
            Tree.copy(repo.resolve(app), copy);
            Path log = root.resolve("test.log");
            if (patch != null) {
                int code = Tree.run(List.of("git", "apply", "--whitespace=nowarn", patch.toAbsolutePath().toString()),
                        root, log, root.toAbsolutePath().getParent(), Duration.ofMinutes(1));
                if (code != 0) throw new IOException("the fix does not apply on a copy of the application: " + patch);
            }
            Path file = copy.resolve(test.path());
            Files.createDirectories(file.getParent());
            Files.writeString(file, test.content());
            List<String> cmd = new ArrayList<>(mvn);
            cmd.addAll(List.of("-B", "-q", "test", "-Dtest=" + test.simpleName(), "-Dsurefire.failIfNoSpecifiedTests=false"));
            Tree.run(cmd, copy, log, null, Duration.ofMinutes(15));
            Run r = report(copy.resolve("target/surefire-reports"), test.className());
            // keep the log next to the work directory for the reviewer of a failed proof
            Files.createDirectories(work.resolve("logs"));
            Files.copy(log, work.resolve("logs").resolve(root.getFileName() + ".log"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return r;
        }

        /** Compiler and build errors of a run (the {@code [ERROR]} lines), else the last lines. */
        static String tail(Path log) {
            try {
                List<String> lines = Files.readAllLines(log);
                List<String> errors = lines.stream().filter(l -> l.startsWith("[ERROR]") && !l.strip().equals("[ERROR]")
                        && !l.contains("Re-run Maven") && !l.contains("-> [Help") && !l.contains("For more information"))
                        .limit(15).toList();
                List<String> out = errors.isEmpty() ? lines.subList(Math.max(0, lines.size() - 15), lines.size()) : errors;
                return String.join("\n", out);
            } catch (IOException e) {
                return "(no log)";
            }
        }
    }
}
