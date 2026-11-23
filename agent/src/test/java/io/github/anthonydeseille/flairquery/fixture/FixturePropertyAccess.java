package io.github.anthonydeseille.flairquery.fixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** @Id on the getter: property access, Hibernate calls the getters itself, so they are not tracked. */
@Entity
public class FixturePropertyAccess {

    private Long id = 1L;

    @Id
    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }
}
