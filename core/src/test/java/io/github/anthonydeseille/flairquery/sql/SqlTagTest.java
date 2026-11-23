package io.github.anthonydeseille.flairquery.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTagTest {

    @Test
    void formatsTheDocumentedTag() {
        String tagged = SqlTag.tag("select * from author where id = ?", "demo", "GET /authors/{id}",
                "com.x.AuthorService#findAll:42");

        assertThat(tagged).isEqualTo(
                "/* fq:app=demo,endpoint=GET /authors/{id},caller=com.x.AuthorService#findAll:42 */ "
                        + "select * from author where id = ?");
    }

    @Test
    void onlyPrependsAComment() {
        String sql = "select a.id, a.name from author a where a.name like '%*/%' -- trailing\n";
        String tagged = SqlTag.tag(sql, "demo", "GET /x", "c#m:1");

        assertThat(tagged).endsWith(sql);
        assertThat(SqlTag.strip(tagged)).isEqualTo(sql);
    }

    @Test
    void isIdempotent() {
        String once = SqlTag.tag("select 1", "demo", "GET /x", "c#m:1");
        assertThat(SqlTag.tag(once, "other", "GET /y", "d#n:2")).isEqualTo(once);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "GET /x */ drop table author; /*",
            "GET /static/**",
            "GET /x/*/y",
            "evil'quote\"double\\back",
            "line\nbreak\r\ttab\u0000nul",
            "a,endpoint=forged",
            "what?param",
            "unicodé – ✓",
            "semi;colon"})
    void sanitizedValuesCannotBreakOutOfTheComment(String hostile) {
        String value = SqlTag.sanitize(hostile);

        assertThat(value).doesNotContain("*", "/*", "*/", "'", "\"", "\\", "\n", "\r", "\t", "\u0000",
                ",", "=", "?", ";");
        assertThat(value.chars()).allMatch(c -> c >= 0x20 && c < 0x7f);

        String tagged = SqlTag.tag("select 1", "demo", hostile, hostile);
        // exactly one comment opener and one closer, both ours
        assertThat(count(tagged, "/*")).isEqualTo(1);
        assertThat(count(tagged, "*/")).isEqualTo(1);
        assertThat(SqlTag.strip(tagged)).isEqualTo("select 1");
    }

    @Test
    void sanitizeTruncatesAndDefaults() {
        assertThat(SqlTag.sanitize("x".repeat(1000))).hasSize(SqlTag.MAX_VALUE_LENGTH);
        assertThat(SqlTag.sanitize(null)).isEqualTo(SqlTag.UNKNOWN);
        assertThat(SqlTag.sanitize("")).isEqualTo(SqlTag.UNKNOWN);
        assertThat(SqlTag.sanitize("   ")).isEqualTo(SqlTag.UNKNOWN);
        assertThat(SqlTag.sanitize("GET /authors/{id}")).isEqualTo("GET /authors/{id}");
        assertThat(SqlTag.sanitize("com.x.Outer$Inner#<init>:7")).isEqualTo("com.x.Outer$Inner#<init>:7");
    }

    @Test
    void parsesTagFromPgStatStatementsText() {
        // pg_stat_statements keeps the comment of the first occurrence and replaces constants by $n
        String text = "/* fq:app=demo,endpoint=GET /authors/{id},caller=com.x.Svc#find:42 */ "
                + "select a1_0.id from author a1_0 where a1_0.id=$1";

        SqlTag.FqTag tag = SqlTag.parse(text).orElseThrow();

        assertThat(tag.app()).isEqualTo("demo");
        assertThat(tag.endpoint()).isEqualTo("GET /authors/{id}");
        assertThat(tag.caller()).isEqualTo("com.x.Svc#find:42");
    }

    @Test
    void parseIsTolerantToUnknownKeysAndMissingTag() {
        assertThat(SqlTag.parse("select 1")).isEmpty();
        assertThat(SqlTag.parse(null)).isEmpty();
        assertThat(SqlTag.parse("/* fq:v=2,app=a,extra=z */ select 1").orElseThrow().all())
                .containsEntry("v", "2").containsEntry("extra", "z");
    }

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + 1)) n++;
        return n;
    }
}
