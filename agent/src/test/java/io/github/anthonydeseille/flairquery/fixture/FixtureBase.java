package io.github.anthonydeseille.flairquery.fixture;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

/** Mapped parent: its attributes belong to every entity extending it. */
@MappedSuperclass
public abstract class FixtureBase {

    @Column(name = "created_by")
    protected String createdBy = "system";

    public String getCreatedBy() { return createdBy; }
}
