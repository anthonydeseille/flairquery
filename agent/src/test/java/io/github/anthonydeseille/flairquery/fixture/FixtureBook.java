package io.github.anthonydeseille.flairquery.fixture;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Transient;

import java.util.List;

/** Entity fixture covering the cases of the model reader (never persisted: no JPA provider in the tests). */
@Entity
public class FixtureBook extends FixtureBase {

    public static final String CONSTANT = "not an attribute";

    @Id
    private Long id = 1L;
    private String title = "t";
    @Column(columnDefinition = "text")
    private String description = "d";
    @Lob
    private byte[] cover;
    @Column(length = 4000)
    private String summary;
    @Column(length = 200)
    private String isbn;
    private boolean available = true;
    @ManyToOne
    private FixtureBase publisher;               // EAGER by JPA default
    @ManyToOne(fetch = FetchType.LAZY)
    private FixtureBase author;
    @OneToMany(mappedBy = "book")
    private List<FixtureBase> reviews;           // LAZY by default
    @ManyToMany(fetch = FetchType.EAGER)
    private List<FixtureBase> tags;
    @Transient
    private String cache;
    private transient String alsoTransient;

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public byte[] getCover() { return cover; }
    public String getSummary() { return summary; }
    public String getIsbn() { return isbn; }
    public boolean isAvailable() { return available; }
    public FixtureBase getPublisher() { return publisher; }
    public FixtureBase getAuthor() { return author; }
    public List<FixtureBase> getReviews() { return reviews; }
    public List<FixtureBase> getTags() { return tags; }
    public String getCache() { return cache; }
    /** Not a persistent attribute's getter: ignored. */
    public String getLabel() { return title + "!"; }
}
