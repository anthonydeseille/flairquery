package io.github.anthonydeseille.flairquery.analyzer.size;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code prove-infra}: replays every sizing recommendation of an overlay on kind (phase 6, step 3), see
 * {@link InfraProver}.
 */
public final class InfraProveCommand {

    public static final String USAGE = "usage: prove-infra --overlay DIR --out DIR [--context kind-flairquery] "
            + "[--kubectl kubectl] [--namespace fq-prove] [--profile S] [--rounds 60] [--interval 10] "
            + "[--load-url http://demo-app:8080] [--finding ID[,ID...]] [--keep true] [--database postgres] [--repo .]";

    private InfraProveCommand() { }

    public static int run(Map<String, String> opt) throws Exception {
        if (!opt.containsKey("overlay") || !opt.containsKey("out")) {
            System.err.println(USAGE);
            return 2;
        }
        Path repo = Path.of(opt.getOrDefault("repo", "."));
        Path overlay = Path.of(opt.get("overlay"));
        if (!Files.isRegularFile(overlay.resolve("kustomization.yaml"))) {
            System.err.println("not a Kustomize overlay: " + overlay);
            return 2;
        }
        String namespace = opt.getOrDefault("namespace", "fq-prove");
        if (!namespace.startsWith("fq-prove")) {
            // the namespace is deleted at the start and at the end: never one that holds anything else
            System.err.println("the replay namespace must start with fq-prove (it is deleted): " + namespace);
            return 2;
        }
        Set<String> only = opt.containsKey("finding")
                ? Arrays.stream(opt.get("finding").split(",")).map(String::strip).filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet())
                : Set.of();
        InfraProver.Options o = new InfraProver.Options(repo, overlay, Path.of(opt.get("out")), namespace,
                opt.getOrDefault("profile", "S"), Integer.parseInt(opt.getOrDefault("rounds", "60")),
                Integer.parseInt(opt.getOrDefault("interval", "10")), opt.getOrDefault("load-url", "http://demo-app:8080"),
                only, Boolean.parseBoolean(opt.getOrDefault("keep", "false")), opt.getOrDefault("database", "postgres"));
        return new InfraProver(o, opt.getOrDefault("kubectl", "kubectl"), opt.getOrDefault("context", "kind-flairquery")).run();
    }
}
