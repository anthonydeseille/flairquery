package io.github.anthonydeseille.flairquery.fixture;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Mimics Spring's DispatcherServlet: maps the handler (sets the pattern attribute), then runs SQL. */
public class DemoServlet extends HttpServlet {

    public static final String PATTERN_ATTR = "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";

    public final List<String> seenSql = new ArrayList<>();
    public volatile String pattern;
    public volatile Callable<String> beforeMapping;
    public volatile Callable<String> afterMapping;
    public volatile RuntimeException failure;

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) {
        try {
            if (beforeMapping != null) {
                seenSql.add(beforeMapping.call());
            }
            if (pattern != null) {
                req.setAttribute(PATTERN_ATTR, pattern);
            }
            if (afterMapping != null) {
                seenSql.add(afterMapping.call());
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (failure != null) {
            throw failure;
        }
    }
}
