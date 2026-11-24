package io.github.anthonydeseille.flairquery.analyzer.fix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** Generated diffs are applied by {@code git apply}, as the prover does: the result must be the new text. */
class UnifiedDiffTest {

    @TempDir
    Path dir;

    @Test
    void anUnchangedFileHasNoDiff() {
        assertThat(UnifiedDiff.of("a.txt", "x\n", "x\n")).isEmpty();
    }

    @Test
    void aNewFile() throws Exception {
        String diff = UnifiedDiff.of("app/new.sql", null, "one\ntwo\n");
        assertThat(diff).startsWith("diff --git a/app/new.sql b/app/new.sql\nnew file mode 100644\n--- /dev/null\n+++ b/app/new.sql\n@@ -0,0 +1,2 @@\n");
        assertThat(applied(null, "one\ntwo\n", diff)).isEqualTo("one\ntwo\n");
    }

    @Test
    void oneLineChangedInTheMiddle() throws Exception {
        String before = "1\n2\n3\n4\n5\n6\n7\n8\n9\n";
        String after = "1\n2\n3\n4\nfive\n6\n7\n8\n9\n";
        String diff = UnifiedDiff.of("f.txt", before, after);
        assertThat(diff).contains("@@ -2,7 +2,7 @@\n 2\n 3\n 4\n-5\n+five\n 6\n");
        assertThat(applied(before, after, diff)).isEqualTo(after);
    }

    @Test
    void additionsAtBothEndsAndMissingFinalNewlines() throws Exception {
        assertRoundTrip("a\nb\nc", "top\na\nb\nc\nbottom");
        assertRoundTrip("a\nb\nc\n", "a\nb\nc");
        assertRoundTrip("a\nb\nc", "a\nb\nc\n");
        assertRoundTrip("", "a\n");
        assertRoundTrip("a\n", "");
    }

    @Test
    void randomEditsRoundTrip() throws Exception {
        Random r = new Random(42);
        for (int n = 0; n < 40; n++) {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < 5 + r.nextInt(40); i++) lines.add("line " + r.nextInt(12));
            List<String> edited = new ArrayList<>(lines);
            for (int e = 0; e < 1 + r.nextInt(6); e++) {
                int at = r.nextInt(edited.size() + 1);
                switch (r.nextInt(3)) {
                    case 0 -> edited.add(at, "new " + r.nextInt(1000));
                    case 1 -> { if (at < edited.size()) edited.remove(at); }
                    default -> { if (at < edited.size()) edited.set(at, "changed " + r.nextInt(1000)); }
                }
            }
            assertRoundTrip(String.join("\n", lines) + "\n", String.join("\n", edited) + (r.nextBoolean() ? "\n" : ""));
        }
    }

    private void assertRoundTrip(String before, String after) throws Exception {
        assertThat(applied(before, after, UnifiedDiff.of("f.txt", before, after))).isEqualTo(after);
    }

    /** Writes {@code before}, applies the diff with git in an empty directory, returns the file. */
    private String applied(String before, String after, String diff) throws Exception {
        Path work = Files.createTempDirectory(dir, "apply");
        String path = diff.substring("diff --git a/".length(), diff.indexOf(' ', "diff --git a/".length()));
        Path file = work.resolve(path);
        Files.createDirectories(file.getParent());
        if (before != null) Files.writeString(file, before);
        Path patch = work.resolve("p.patch");
        Files.writeString(patch, diff);
        ProcessBuilder pb = new ProcessBuilder("git", "apply", "--whitespace=nowarn", patch.toString())
                .directory(work.toFile()).redirectErrorStream(true);
        pb.environment().put("GIT_CEILING_DIRECTORIES", work.getParent().toString());
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as("git apply: " + output + "\n" + diff).isZero();
        return Files.exists(file) ? Files.readString(file) : "";
    }
}
