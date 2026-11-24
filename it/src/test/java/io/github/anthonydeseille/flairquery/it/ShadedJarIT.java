package io.github.anthonydeseille.flairquery.it;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.assertThat;

/** The agent jar can never clash with application dependencies. */
class ShadedJarIT {

    @Test
    void everyClassIsInTheFlairQueryNamespace() throws Exception {
        try (JarFile jar = new JarFile(DemoAppProcess.agentJar().toFile())) {
            List<String> foreign = jar.stream()
                    .map(ZipEntry::getName)
                    .filter(n -> !n.endsWith("/"))
                    .filter(n -> !n.startsWith("io/github/anthonydeseille/flairquery/"))
                    .filter(n -> !n.equals("META-INF/MANIFEST.MF"))
                    .filter(n -> !n.startsWith("META-INF/licenses/"))
                    // Byte Buddy's LICENSE and NOTICE, kept as Apache 2.0 requires (not classes: nothing can clash)
                    .filter(n -> !n.equals("META-INF/LICENSE") && !n.equals("META-INF/NOTICE"))
                    .toList();
            assertThat(foreign).as("entries outside io/github/anthonydeseille/flairquery/").isEmpty();

            Manifest mf = jar.getManifest();
            assertThat(mf.getMainAttributes().getValue("Premain-Class"))
                    .isEqualTo("io.github.anthonydeseille.flairquery.agent.FqAgent");
            assertThat(mf.getMainAttributes().getValue("Multi-Release")).isNull();
        }
    }
}
