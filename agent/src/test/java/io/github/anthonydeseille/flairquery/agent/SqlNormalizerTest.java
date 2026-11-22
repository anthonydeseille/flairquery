package io.github.anthonydeseille.flairquery.agent;

import io.github.anthonydeseille.flairquery.agent.metrics.SqlNormalizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlNormalizerTest {

    @Test
    void replacesLiteralsAndCollapsesWhitespace() {
        assertThat(SqlNormalizer.normalize("select *  from book\n where id = 42 and title = 'L''Étranger'  and price > 3.5"))
                .isEqualTo("select * from book where id = ? and title = ? and price > ?");
    }

    @Test
    void keepsIdentifiersContainingDigits() {
        assertThat(SqlNormalizer.normalize("select b1_0.id, b1_0.publisher_id from book b1_0 where b1_0.author_id=?"))
                .isEqualTo("select b1_0.id, b1_0.publisher_id from book b1_0 where b1_0.author_id=?");
    }

    @Test
    void positionalParametersAndInLists() {
        assertThat(SqlNormalizer.normalize("select * from book where id in ($1, $2, $3) or author_id in (?,?)"))
                .isEqualTo("select * from book where id in (?...) or author_id in (?...)");
        assertThat(SqlNormalizer.normalize("select * from book where id in (?)"))
                .isEqualTo("select * from book where id in (?)");
    }

    @Test
    void sameShapeSameNormalizedText() {
        assertThat(SqlNormalizer.normalize("select * from review where book_id = 1"))
                .isEqualTo(SqlNormalizer.normalize("select * from review where book_id=1".replace("=", " = ")));
    }

    @Test
    void stripsTheFlairQueryTagAndReadsTheCaller() {
        SqlNormalizer n = new SqlNormalizer();
        SqlNormalizer.Parsed p = n.parse("/* fq:app=a,endpoint=GET /x,caller=com.x.Svc#find:12 */ select 1");

        assertThat(p.normalizedSql()).isEqualTo("select ?");
        assertThat(p.caller()).isEqualTo("com.x.Svc#find:12");
        assertThat(n.parse("/* fq:app=a,endpoint=GET /x,caller=com.x.Svc#find:12 */ select 1")).isSameAs(p);
    }
}
