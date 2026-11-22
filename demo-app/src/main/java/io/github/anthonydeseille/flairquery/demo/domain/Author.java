package io.github.anthonydeseille.flairquery.demo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "author")
public class Author {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(columnDefinition = "text")
    private String bio;

    @Column(name = "ol_key")
    private String olKey;

    /** LAZY (JPA default for collections): iterating it per author is TRAP 1 (N+1). */
    @OneToMany(mappedBy = "author")
    private List<Book> books = new ArrayList<>();

    protected Author() { }

    public Long getId() { return id; }
    public String getName() { return name; }
    public LocalDate getBirthDate() { return birthDate; }
    public String getBio() { return bio; }
    public String getOlKey() { return olKey; }
    public List<Book> getBooks() { return books; }
}
