package io.github.anthonydeseille.flairquery.agent;

import io.github.anthonydeseille.flairquery.agent.model.EntityModel;
import io.github.anthonydeseille.flairquery.agent.model.EntityModelReader;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The model is read from class files through a type pool, as the agent does before a class is loaded. */
class EntityModelReaderTest {

    private static final String FIXTURE = "io.github.anthonydeseille.flairquery.fixture.";

    static TypeDescription described(String simpleName) {
        TypePool pool = TypePool.Default.of(ClassFileLocator.ForClassLoader.of(EntityModelReaderTest.class.getClassLoader()));
        return pool.describe(FIXTURE + simpleName).resolve();
    }

    @Test
    void readsKindsFetchTypesAndLargeColumns() {
        TypeDescription type = described("FixtureBook");
        assertThat(EntityModelReader.isEntity(type)).isTrue();

        EntityModel m = EntityModelReader.read(type);
        Map<String, EntityModel.Attribute> a = m.attributes().stream()
                .collect(Collectors.toMap(EntityModel.Attribute::name, x -> x));

        assertThat(m.propertyAccess()).isFalse();
        assertThat(a).containsOnlyKeys("createdBy", "id", "title", "description", "cover", "summary", "isbn",
                "available", "publisher", "author", "reviews", "tags");   // static, @Transient, transient skipped
        assertThat(a.get("id").kind()).isEqualTo(EntityModel.Kind.ID);
        assertThat(a.get("title").kind()).isEqualTo(EntityModel.Kind.BASIC);
        // to-one: EAGER by default (trap 2), explicit LAZY kept
        assertThat(a.get("publisher")).satisfies(x -> {
            assertThat(x.kind()).isEqualTo(EntityModel.Kind.TO_ONE);
            assertThat(x.fetch()).isEqualTo("EAGER");
            assertThat(x.fetchExplicit()).isFalse();
        });
        assertThat(a.get("author").fetch()).isEqualTo("LAZY");
        assertThat(a.get("author").fetchExplicit()).isTrue();
        // to-many: LAZY by default, explicit EAGER kept
        assertThat(a.get("reviews").kind()).isEqualTo(EntityModel.Kind.TO_MANY);
        assertThat(a.get("reviews").fetch()).isEqualTo("LAZY");
        assertThat(a.get("tags").fetch()).isEqualTo("EAGER");
        // large columns: TEXT, @Lob byte[], VARCHAR(4000); not VARCHAR(200) nor a plain String
        assertThat(a.values().stream().filter(EntityModel.Attribute::large).map(EntityModel.Attribute::name))
                .containsExactlyInAnyOrder("description", "cover", "summary");
        // @MappedSuperclass attributes come first
        assertThat(m.attributes().get(0).name()).isEqualTo("createdBy");
    }

    @Test
    void gettersMapToAttributes() {
        EntityModel m = EntityModelReader.read(described("FixtureBook"));
        assertThat(m.attributeOfGetter("getTitle")).isEqualTo(index(m, "title"));
        assertThat(m.attributeOfGetter("isAvailable")).isEqualTo(index(m, "available"));
        assertThat(m.attributeOfGetter("getCreatedBy")).isEqualTo(index(m, "createdBy"));
        assertThat(m.attributeOfGetter("getLabel")).isEqualTo(-1);      // computed, not persistent
        assertThat(m.attributeOfGetter("getCache")).isEqualTo(-1);      // @Transient
    }

    @Test
    void propertyAccessIsDetected() {
        assertThat(EntityModelReader.read(described("FixturePropertyAccess")).propertyAccess()).isTrue();
        assertThat(EntityModelReader.isEntity(described("FixtureBase"))).isFalse();
    }

    static int index(EntityModel m, String name) {
        return m.attributes().stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow().index();
    }
}
