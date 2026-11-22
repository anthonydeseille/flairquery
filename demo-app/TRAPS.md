# Pièges volontaires de la demo-app (vérité terrain)

Ce fichier est la **vérité terrain** de FlairQuery : à chaque phase, on mesure combien de pièges sont
détectés (phase 1-3) puis corrigés et prouvés (phase 4+). Ne pas corriger les pièges dans la demo-app :
les corrections attendues décrivent ce que FlairQuery doit *proposer*.

Données : corpus déterministe (`corpus/generate.py`), auteur 7 = auteur prolifique (5000 livres),
reviewer connu `Alice Martin 00000`, éditeur témoin 1.

| # | Piège | Endpoint | Où dans le code | Signal attendu | Détecté en phase 1 ? | Correction attendue |
|---|---|---|---|---|---|---|
| 1 | **N+1** auteurs → livres | `GET /authors?limit=20` | `AuthorController#toDto` (lecture de `author.getBooks()` par auteur) | 1 requête auteurs + 1 requête livres **par auteur** | ✅ `/fq/nplusone` (21 requêtes pour 20 auteurs) | `@EntityGraph(attributePaths = "books")` ou `JOIN FETCH` dédié, ou `@BatchSize` / `hibernate.default_batch_fetch_size`, ou projection DTO |
| 2 | **`@ManyToOne` EAGER par défaut** `Book → Publisher`, jamais lu | `GET /books?limit=50` | `Book.publisher` sans `fetch = LAZY` | 1 `select ... from publisher where id=?` par éditeur distinct | ✅ indirectement : visible comme N+1 sur `publisher` (aussi sur `/books/titles` et `/authors/with-books`). Le diagnostic « EAGER inutile » est phase 3 | `@ManyToOne(fetch = FetchType.LAZY)` |
| 3 | **Sur-fetch** : entités complètes (dont `description` TEXT ~600 car.) pour renvoyer 2 champs | `GET /books/titles?authorId=7` | `BookService#titlesOfAuthor` | `select b1_0.id, ..., b1_0.description, ...` + éditeurs EAGER | ⚠️ partiel : visible dans `/fq/top` (temps, colonnes du SQL). Détection formelle phase 3 | Projection `select new BookTitleDto(b.id, b.title)` ou interface projection |
| 4 | **Requête lente sans index** | `GET /reviews/search?reviewer=Alice%20Martin%2000000` | `ReviewRepository#searchByReviewer` | `Seq Scan on review` (plus grosse table) | ⚠️ partiel : visible dans `/fq/top` et `pg_stat_statements`. Plan (auto_explain) exploité phase 2 | Index `CREATE INDEX CONCURRENTLY idx_review_reviewer ON review (reviewer_name)` (changeset Liquibase), validé par HypoPG |
| 5 | **`JOIN FETCH` + pagination** | `GET /authors/with-books?prefix=Al&size=5` | `AuthorRepository#findWithBooksByNamePrefix` | Warning `HHH90003004` (pagination en mémoire), tous les auteurs `Al%` et tous leurs livres chargés | ⚠️ partiel : volume visible dans `/fq/endpoints` ; le warning est dans les logs de l'appli. Détection phase 3 | Paginer les ids d'abord puis `JOIN FETCH ... where a.id in :ids`, ou `@EntityGraph` + `@BatchSize` |
| 6 | **`spring.jpa.open-in-view=true`** laissé par défaut | global (aggrave #1) | `application.yml` (propriété absente) | Warning Spring au démarrage ; le N+1 de #1 a lieu dans la couche web (`caller=...AuthorController#toDto`) | ⚠️ indice : caller dans un `*Controller` hors transaction. Détection phase 3 | `spring.jpa.open-in-view: false` + chargement explicite dans le service |
| 7 | **Tri volumineux qui déborde sur disque** | `GET /reviews/ranking?page=400&size=50` | `ReviewRepository#ranking` (`order by created_at desc, comment` + OFFSET 20000) | `Sort Method: external merge Disk`, `temp_blks_written > 0` dans `pg_stat_statements` | ⚠️ partiel : `/fq/top` + `temp_blks_written`. Analyse phase 2 / dimensionnement `work_mem` phase 6 | Index `(created_at desc)` + pagination keyset ; ou `work_mem` ajusté (phase 6, après correction du code) |
| ✓ | **Témoin propre** | `GET /publishers/1` | `PublisherRepository#findDtoById` | 1 requête, PK, projection DTO | ✅ **jamais signalé** | — |

## Mesures de référence (profil S, local, agent actif)

| Endpoint | Requêtes SQL / appel | Remarque |
|---|---|---|
| `GET /authors?limit=20` | 21-22 | N+1 (#1) |
| `GET /books?limit=50` | ~45 | EAGER publisher (#2) |
| `GET /books/titles?authorId=7` | ~501 | 1 + 500 éditeurs distincts (#2 + #3) |
| `GET /authors/with-books?prefix=Al&size=5` | ~326 | #5 + #2 |
| `GET /reviews/search` | 1 | seq scan (#4) |
| `GET /reviews/ranking` | 1 | external merge (#7) |
| `GET /publishers/1` | 1 | témoin |

## Limite constatée (phase 1)

`pg_stat_statements` ne conserve que le texte (donc le tag) de la **première** occurrence d'un `queryid`.
Exemple mesuré : la requête `select ... from publisher where id=?` est exécutée par trois endpoints
(#2, #3, #5), mais `pg_stat_statements` l'attribue entièrement au premier tag vu (`GET /books`).
La répartition exacte par endpoint et par caller est fournie par l'agent (`/fq/top`, `/fq/nplusone`).
