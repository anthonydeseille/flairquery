package io.github.anthonydeseille.flairquery.fixture;

import java.util.function.Supplier;

/** Base-package class used to check caller resolution. */
public class AuthorService {

    public static volatile int findAllLine;

    public String findAll(Supplier<String> probe) {
        findAllLine = new Throwable().getStackTrace()[0].getLineNumber() + 1;
        return probe.get();
    }

    public String viaLambda(Supplier<String> probe) {
        Supplier<String> lambda = () -> probe.get();
        return lambda.get();
    }
}
