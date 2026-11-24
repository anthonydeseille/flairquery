package io.github.anthonydeseille.flairquery.analyzer.mr;

import io.github.anthonydeseille.flairquery.analyzer.fix.UnifiedDiff;

/**
 * A regression test added to the application with a fix: a new JUnit class that fails on the current code (the
 * problem is there) and passes with the fix.
 *
 * @param path        relative to the application directory ({@code src/test/java/.../XxxRegressionTest.java})
 * @param className   fully qualified
 * @param description one sentence for the reviewer: what it sets up and asserts
 */
public record RegressionTest(String path, String className, String content, String description) {

    public String simpleName() {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /** The test as a patch adding its file, path prefixed with the application directory ({@code demo-app}). */
    public String patch(String appPrefix) {
        String prefix = appPrefix == null || appPrefix.isEmpty() || appPrefix.equals(".") ? ""
                : appPrefix.replace('\\', '/').replaceAll("/+$", "") + "/";
        return UnifiedDiff.of(prefix + path, null, content);
    }
}
