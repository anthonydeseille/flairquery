package io.github.anthonydeseille.flairquery.analyzer.prove;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Copies and patches on the real demo sources, with a fake build (no Maven). */
class WorkspaceTest {

    static final Path REPO = Path.of(System.getProperty("fq.demo.sources", "../demo-app")).toAbsolutePath()
            .normalize().getParent();
    static final Path PATCHES = Path.of("src/test/resources/patches").toAbsolutePath();
    static final String BOOK = "demo-app/src/main/java/io/github/anthonydeseille/flairquery/demo/domain/Book.java";

    @TempDir
    Path work;

    Workspace workspace() {
        return new Workspace(new Workspace.Config(REPO, "demo-app", work.resolve("prover"),
                List.of("sh", "-c", "mkdir -p target && touch target/app.jar"), "target/app.jar"));
    }

    @Test
    void aPatchIsAppliedOnACopyNeverOnTheRepository() throws Exception {
        String original = Files.readString(REPO.resolve(BOOK));

        Path jar = workspace().prepare("candidate-1", PATCHES.resolve("publisher-lazy.patch"));

        assertThat(jar).exists();
        assertThat(Files.readString(work.resolve("prover/candidate-1").resolve(BOOK)))
                .contains("@ManyToOne(fetch = FetchType.LAZY)\n    @JoinColumn(name = \"publisher_id\")");
        assertThat(Files.readString(REPO.resolve(BOOK))).isEqualTo(original);
        assertThat(work.resolve("prover/candidate-1/demo-app/target/flairquery-demo-app.jar")).doesNotExist();
    }

    @Test
    void aPatchOutsideTheApplicationIsRefused() throws Exception {
        Path patch = work.resolve("outside.patch");
        Files.writeString(patch, """
                diff --git a/README.md b/README.md
                --- a/README.md
                +++ b/README.md
                @@ -1 +1 @@
                -x
                +y
                """);
        assertThatThrownBy(() -> workspace().prepare("candidate-1", patch))
                .isInstanceOf(Workspace.Failure.class).hasMessageContaining("outside the application directory");
    }

    @Test
    void aPatchThatDoesNotApplyIsRefused() throws Exception {
        Path patch = work.resolve("stale.patch");
        Files.writeString(patch, Files.readString(PATCHES.resolve("publisher-lazy.patch"))
                .replace("-    @ManyToOne\n", "-    @ManyToOne(optional = false)\n"));
        assertThatThrownBy(() -> workspace().prepare("candidate-1", patch))
                .isInstanceOf(Workspace.Failure.class).hasMessageContaining("does not apply");
    }

    @Test
    void aBuildFailureIsReported() {
        Workspace ws = new Workspace(new Workspace.Config(REPO, "demo-app", work.resolve("prover"),
                List.of("sh", "-c", "echo 'COMPILATION ERROR'; exit 1"), "target/app.jar"));
        assertThatThrownBy(() -> ws.prepare("candidate-1", PATCHES.resolve("publisher-lazy.patch")))
                .isInstanceOf(Workspace.Failure.class).hasMessageContaining("does not build").hasMessageContaining("COMPILATION ERROR");
    }

    @Test
    void requestsFileAndJarName() throws Exception {
        List<RequestSpec> r = RequestSpec.read(REPO.resolve("analyzer/requests-demo.txt"));
        assertThat(r).hasSize(7);
        assertThat(r).filteredOn(x -> !x.compareBody()).extracting(RequestSpec::path)
                .containsExactly("/reviews/ranking?page=400&size=50");
        assertThat(ProveCommand.defaultJarName(REPO.resolve("demo-app"))).isEqualTo("flairquery-demo-app.jar");
    }
}
