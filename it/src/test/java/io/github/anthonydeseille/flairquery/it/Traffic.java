package io.github.anthonydeseille.flairquery.it;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Same endpoints as scripts/traffic.sh (traps + witness). */
final class Traffic {

    static final String AUTHORS = "/authors?limit=20";
    static final String WITNESS = "/publishers/1";

    /** Endpoints whose JSON body is deterministic (used to compare with / without agent). */
    static final List<String> DETERMINISTIC = List.of(
            AUTHORS,
            "/books?limit=50",
            "/books/titles?authorId=7",
            "/reviews/search?reviewer=Alice%20Martin%2000000",
            "/authors/with-books?prefix=Al&size=5",
            WITNESS);

    static final List<String> ALL = List.of(
            AUTHORS,
            "/books?limit=50",
            "/books/titles?authorId=7",
            "/reviews/search?reviewer=Alice%20Martin%2000000",
            "/authors/with-books?prefix=Al&size=5",
            "/reviews/ranking?page=400&size=50",
            WITNESS);

    private Traffic() { }

    static void run(DemoAppProcess app, int rounds) throws Exception {
        for (int i = 0; i < rounds; i++) {
            for (String path : ALL) {
                app.getOk(path);
            }
        }
    }

    static Map<String, String> bodies(DemoAppProcess app) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (String path : DETERMINISTIC) {
            out.put(path, app.getOk(path));
        }
        return out;
    }
}
