# FlairQuery

**Analyse des requêtes SQL et des endpoints d'applications Java / Spring / JPA, sur Kubernetes.**

Un agent Java instrumente l'application **sans modification de code** et tague chaque requête SQL avec
l'endpoint HTTP et la ligne de code appelante :

```sql
/* fq:app=demo-app,endpoint=GET /authors,caller=io.github.anthonydeseille.flairquery.demo.web.AuthorController#toDto:47 */
select b1_0.author_id, ... from book b1_0 left join publisher p1_0 on ... where b1_0.author_id=?
```

Il mesure le nombre et le temps des requêtes par requête HTTP et détecte les **N+1**. Il expose aussi
des métriques Prometheus et des vues JSON. Les phases suivantes ajouteront la corrélation avec
`pg_stat_statements` / `auto_explain`, le profil par endpoint, les correctifs prouvés proposés en merge
request, le redimensionnement de l'infrastructure et un opérateur Kubernetes : voir
[docs/ROADMAP.md](docs/ROADMAP.md).

> **Contraintes non négociables** : lecture seule sur la base, aucune réécriture SQL autre que le
> commentaire de tag, fail-safe absolu (l'outil n'impacte jamais l'application observée), désactivable
> entièrement (`FQ_ENABLED=false`), aucune dépendance externe ni LLM en phase 1.

## Sommaire

- [État de la phase 1](#état-de-la-phase-1)
- [Contenu du dépôt](#contenu-du-dépôt)
- [Démarrage rapide (local, sans Kubernetes)](#démarrage-rapide-local-sans-kubernetes)
- [Installer l'agent sur une application](#installer-lagent-sur-une-application)
- [Configuration](#configuration)
- [Le tag SQL](#le-tag-sql)
- [Endpoints HTTP et exemples de sortie](#endpoints-http-et-exemples-de-sortie)
- [Demo-app et pièges](#demo-app-et-pièges)
- [Corpus](#corpus)
- [Tests et CI](#tests-et-ci)
- [Surcoût mesuré](#surcoût-mesuré)
- [Kubernetes (kind) et plugin K9s](#kubernetes-kind-et-plugin-k9s)
- [Garanties fail-safe](#garanties-fail-safe)
- [Limites connues](#limites-connues)

## État de la phase 1

| Étape | Contenu | État |
|---|---|---|
| Agent — tagging | Tag `fq:` (endpoint + caller) sur JDBC, contexte de requête servlet | ✅ |
| Agent — mesure + HTTP | Comptage et temps par requête HTTP, N+1, `/metrics`, `/fq/top`, `/fq/nplusone` | ✅ |
| Demo-app + pièges | Spring Boot 3.5, Hibernate 6.6, 7 pièges + 1 témoin ([TRAPS.md](demo-app/TRAPS.md)) | ✅ |
| Corpus S et M | Générateur déterministe, `COPY` en flux, extrait Open Library | ✅ |
| Tests d'intégration | Testcontainers + vrai jar de l'agent, CI GitHub Actions | ✅ |
| Surcoût | Latence p50/p99 avec et sans agent | ✅ (local) |
| Déploiement kind + Kustomize | Overlays `dev` / `e2e`, initContainer d'injection | ⏳ à venir |
| Plugin K9s | `/fq/top` et `/fq/nplusone` depuis K9s | ⏳ à venir |
| E2E kind + CI e2e | `make e2e`, namespace éphémère `fq-e2e` | ⏳ à venir |

## Contenu du dépôt

```
flairquery/
├── agent/          flairquery-agent : agent Java 21 (ByteBuddy relocalisé, jar shadé sans dépendance)
├── demo-app/       flairquery-demo-app : appli Spring Boot cible, pièges documentés dans TRAPS.md
├── it/             flairquery-it : tests d'intégration (Testcontainers + agent réel)
├── corpus/         générateur de données (Python stdlib), chargement COPY, extrait Open Library
├── deploy/         Postgres instrumenté (config + init), environnement local Docker Compose
├── scripts/        trafic (traffic.sh), mesure du surcoût (bench.sh)
├── docs/ROADMAP.md phases 2 à 7 (guide de conception)
└── Makefile        point d'entrée (make help)
```

Maven multi-module (`./mvnw`, rien à installer) : `groupId io.github.anthonydeseille.flairquery`.
La demo-app **n'hérite pas** du parent FlairQuery et ne dépend pas de l'agent.

## Démarrage rapide (local, sans Kubernetes)

Prérequis : JDK 21+, Docker (Docker Desktop, colima…), Python 3, `psql`, `jq`.

```bash
# terminal 1 : Postgres 16 instrumenté (Docker) + demo-app avec l'agent, au premier plan
make demo-local

# terminal 2
make seed-local PROFILE=S          # ~99k lignes, ~15 s (PROFILE=M : 3,65 M lignes, 1,25 Go, 5-8 min)
make traffic-local                 # appelle tous les endpoints de la demo
curl -s localhost:9464/fq/nplusone | jq
curl -s 'localhost:9464/fq/top?limit=5' | jq
curl -s localhost:9464/metrics | grep '^fq_'
```

Les tags sont visibles côté base :

```bash
docker exec flairquery-local-postgres-1 psql -U flairquery -d flairquery \
  -c "select calls, left(query, 160) from pg_stat_statements where query like '/* fq:%' order by total_exec_time desc limit 5"
```

`make demo-local-clean` arrête Postgres **et supprime son volume**. `make help` liste toutes les cibles.

## Installer l'agent sur une application

L'agent est un unique jar, sans dépendance transitive (ByteBuddy est relocalisé sous
`io.github.anthonydeseille.flairquery.shaded`) :

```bash
./mvnw -B -q -pl agent package -DskipTests      # → agent/target/flairquery-agent.jar

FQ_APP_NAME=mon-appli FQ_BASE_PACKAGES=com.monentreprise \
  java -javaagent:/chemin/flairquery-agent.jar -jar mon-appli.jar
```

Sans modifier la ligne de commande (conteneurs) :

```bash
export JAVA_TOOL_OPTIONS="-javaagent:/fq/agent.jar"
```

Sur Kubernetes, un initContainer copiera le jar dans un `emptyDir` monté en `/fq` (étape à venir) ;
en phase 7, un webhook d'admission fera l'injection.

Points instrumentés :

| Point | Rôle |
|---|---|
| `jakarta.servlet.http.HttpServlet#service(HttpServletRequest, HttpServletResponse)` et surcharges | ouvre / ferme le contexte de requête (ThreadLocal + compteur de profondeur, nettoyé même sur exception) |
| `java.sql.Connection#prepareStatement(String, …)` (toutes implémentations, driver et pool) | ajoute le tag, mémorise le SQL du statement (map faible) |
| `java.sql.Statement#execute / executeQuery / executeUpdate / executeLargeUpdate(String, …)` | tag + mesure |
| `execute*()` / `executeBatch()` sans argument (prepared statements) | mesure |
| `Statement#addBatch(String)` | tag |

Compatibilité vérifiée : HikariCP 6 (proxy **et** driver instrumentés, tag et comptage uniques),
pgjdbc 42.7, H2 2.3, Hibernate 6.6, Spring Boot 3.5 (fat jar), Java 21 et 25.

## Configuration

| Variable | Défaut | Rôle |
|---|---|---|
| `FQ_ENABLED` | `true` | `false` : l'agent ne s'installe pas du tout (aucune transformation, aucun port) |
| `FQ_APP_NAME` | `unknown-app` | nom de l'application dans le tag et les métriques |
| `FQ_BASE_PACKAGES` | *(vide)* | packages applicatifs pour le `caller` (séparés par `,`). Vide : première frame hors frameworks connus |
| `FQ_NPLUSONE_THRESHOLD` | `5` | N+1 si un même SQL normalisé est exécuté **plus de** N fois dans une requête HTTP |
| `FQ_SAMPLING_RATE` | `1.0` | fraction des requêtes HTTP taguées et mesurées (décision par requête) |
| `FQ_HTTP_PORT` | `9464` | port du serveur HTTP de l'agent (`0` = port libre) |
| `FQ_LOG_LEVEL` | `INFO` | `DEBUG` liste les classes instrumentées et chaque requête HTTP |

Une valeur invalide est journalisée et remplacée par sa valeur par défaut : la configuration
n'empêche jamais le démarrage.

## Le tag SQL

```
/* fq:app=<FQ_APP_NAME>,endpoint=<METHODE pattern>,caller=<classe#méthode:ligne> */ <SQL d'origine>
```

- **endpoint** : attribut Spring `HandlerMapping.bestMatchingPattern` lu par réflexion (aucune
  dépendance Spring), par exemple `GET /authors/{id}`. Avant le mapping (filtres) ou hors Spring MVC :
  l'URI brute. Hors requête HTTP (démarrage, Liquibase, schedulers) : `none`.
- **caller** : première frame de `FQ_BASE_PACKAGES` trouvée par `StackWalker`, en ignorant les proxies
  Spring CGLIB, Hibernate, ByteBuddy, JDK et les lambdas. Le résultat est mis en cache par frame.
- **Échappement** : chaque valeur passe une liste blanche ASCII. Sont exclus notamment `*` (les
  commentaires PostgreSQL s'imbriquent : un `/*` dans une valeur avalerait la requête), les guillemets,
  `\`, `?`, `;`, `,`, `=` et les caractères de contrôle. Chaque valeur est tronquée à 200 caractères.
- **Idempotent** : un SQL qui commence déjà par `/* fq:` n'est pas re-tagué.
- **Aucune autre modification** : le SQL d'origine suit le commentaire, inchangé (testé).
- `prepareCall` n'est **pas** tagué : la syntaxe d'échappement JDBC `{call …}` doit rester en tête.

Le format et le parsing sont dans une seule classe (`SqlTag`), prête à être réutilisée par le collector
de la phase 2.

## Endpoints HTTP et exemples de sortie

Serveur HTTP du JDK, en lecture seule (`GET` uniquement), sur un thread daemon.

| Chemin | Contenu |
|---|---|
| `/metrics` | format Prometheus texte 0.0.4 |
| `/fq/top?limit=N` | top des SQL par temps total (endpoint, caller, appels, temps) |
| `/fq/nplusone` | N+1 détectés |
| `/fq/endpoints` | synthèse par endpoint (requêtes HTTP, requêtes SQL, temps) |
| `/fq/health` | `{"status":"UP"}` |

Les réponses JSON portent `schemaVersion` (actuellement `1`). Toutes les structures en mémoire sont
bornées (500 endpoints, 5 000 SQL, 1 000 N+1) ; au-delà, les nouvelles clés sont ignorées et comptées
dans `fq_dropped`.

Sorties réelles (demo-app, profil M, `make traffic-local` × 2) :

**`/fq/nplusone`**

```json
{
  "schemaVersion": 1,
  "app": "demo-app",
  "threshold": 5,
  "nplusone": [
    {
      "endpoint": "GET /authors",
      "sql": "select b1_0.author_id,b1_0.id,b1_0.description,b1_0.isbn,b1_0.publication_year,p1_0.id,p1_0.country,p1_0.name,b1_0.title from book b1_0 left join publisher p1_0 on p1_0.id=b1_0.publisher_id where b1_0.author_id=?",
      "caller": "io.github.anthonydeseille.flairquery.demo.web.AuthorController#toDto:47",
      "occurrences": 2,
      "maxQueriesPerRequest": 20,
      "lastQueriesPerRequest": 20,
      "totalQueries": 40,
      "totalTimeMs": 219.779,
      "lastSeen": "2026-11-23T07:47:43.749Z"
    }
  ]
}
```

Le caller pointe sur le **contrôleur** : le N+1 se produit dans la couche web, hors transaction, ce que
seul `open-in-view` rend possible (pièges 1 et 6).

**`/fq/top?limit=2`**

```json
{
  "schemaVersion": 1,
  "app": "demo-app",
  "generatedAt": "2026-11-23T07:48:22.999384Z",
  "top": [
    {
      "endpoint": "GET /reviews/ranking",
      "sql": "select r1_0.id,r1_0.book_id,r1_0.rating,r1_0.reviewer_name,r1_0.created_at from review r1_0 order by r1_0.created_at desc,r1_0.comment offset ? rows fetch first ? rows only",
      "caller": "io.github.anthonydeseille.flairquery.demo.web.ReviewController#ranking:39",
      "callers": ["io.github.anthonydeseille.flairquery.demo.web.ReviewController#ranking:39"],
      "calls": 2,
      "totalTimeMs": 49132.076,
      "avgTimeMs": 24566.038,
      "maxTimeMs": 25048.498
    },
    {
      "endpoint": "GET /authors/with-books",
      "sql": "select p1_0.id,p1_0.country,p1_0.name from publisher p1_0 where p1_0.id=?",
      "caller": "io.github.anthonydeseille.flairquery.demo.service.AuthorService#withBooks:28",
      "callers": ["io.github.anthonydeseille.flairquery.demo.service.AuthorService#withBooks:28"],
      "calls": 9808,
      "totalTimeMs": 14068.034,
      "avgTimeMs": 1.434,
      "maxTimeMs": 6.648
    }
  ]
}
```

Sur le profil M, le tri sur disque du piège 7 coûte 25 s par appel (`work_mem=64kB`, 3 M de lignes).

**`/metrics`** (extrait)

```
fq_queries_count{app="demo-app",endpoint="GET /authors"} 44
fq_queries_time_seconds{app="demo-app",endpoint="GET /authors"} 0.310398289
fq_nplusone{app="demo-app",endpoint="GET /authors"} 2
fq_http_requests_count{app="demo-app",endpoint="GET /authors"} 2
fq_queries_count{app="demo-app",endpoint="GET /publishers/{id}"} 2
fq_nplusone{app="demo-app",endpoint="GET /publishers/{id}"} 0
fq_agent_errors{app="demo-app"} 0
fq_agent_active{app="demo-app"} 1
```

| Métrique | Type | Sens |
|---|---|---|
| `fq_queries_count` | counter | requêtes SQL exécutées, par endpoint |
| `fq_queries_time_seconds` | counter | temps SQL cumulé (appels JDBC `execute*`) |
| `fq_nplusone` | counter | requêtes HTTP dans lesquelles un N+1 a été détecté |
| `fq_http_requests_count` / `fq_http_time_seconds` | counter | requêtes HTTP observées (échantillonnées) |
| `fq_agent_errors` | counter | erreurs internes de l'agent (jamais propagées) |
| `fq_agent_active` | gauge | `0` après ouverture du disjoncteur |
| `fq_dropped` | counter | entrées ignorées car une borne mémoire est atteinte |

**Logs** : JSON sur une ligne, sur stdout ; chaque N+1 est journalisé une seule fois par
(endpoint, SQL) :

```json
{"ts":"2026-11-23T07:47:02.207691Z","level":"WARN","logger":"flairquery-agent","msg":"N+1 detected","endpoint":"GET /authors","sql":"select b1_0.author_id,... where b1_0.author_id=?","caller":"io.github.anthonydeseille.flairquery.demo.web.AuthorController#toDto:47","queriesInRequest":20,"threshold":5}
```

**Côté PostgreSQL** (`pg_stat_statements.query`) :

```
/* fq:app=demo-app,endpoint=GET /authors,caller=io.github.anthonydeseille.flairquery.demo.service.AuthorService#firstAuthors:23 */ select count(a1_0.id) from author a1_0
```

## Demo-app et pièges

Spring Boot 3.5.16, Hibernate 6.6, PostgreSQL 16, schéma géré par Liquibase (données jamais chargées par
Liquibase). Entités `Author`, `Book`, `Publisher`, `Review`.

| # | Piège | Endpoint |
|---|---|---|
| 1 | N+1 auteurs → livres | `GET /authors?limit=20` |
| 2 | `@ManyToOne` EAGER par défaut (`Book → Publisher`), jamais lu | `GET /books?limit=50` |
| 3 | Sur-fetch : entités complètes pour 2 champs | `GET /books/titles?authorId=7` |
| 4 | Filtre sur colonne non indexée | `GET /reviews/search?reviewer=Alice%20Martin%2000000` |
| 5 | `JOIN FETCH` + pagination (HHH90003004) | `GET /authors/with-books?prefix=Al&size=5` |
| 6 | `spring.jpa.open-in-view=true` par défaut | global |
| 7 | Tri volumineux qui déborde sur disque | `GET /reviews/ranking?page=400&size=50` |
| ✓ | Témoin propre | `GET /publishers/1` |

[demo-app/TRAPS.md](demo-app/TRAPS.md) est la **vérité terrain** : signal attendu, détection en
phase 1 et correction attendue pour chaque piège. Ce qui est vérifié de bout en bout : N+1 et EAGER
détectés, seq scan, `external merge Disk` et `temp_blks_written`, warnings `HHH90003004` et
`open-in-view`, témoin jamais signalé.

## Corpus

Données chargées **uniquement par `COPY`**, générées en flux (aucun CSV sur disque), déterministes
(seed fixe). Détails, volumes et extrait Open Library : [corpus/README.md](corpus/README.md).

| Profil | Usage | Lignes | Disque | Chargement local |
|---|---|---|---|---|
| S | Testcontainers, CI | ~99 k | 41 Mo | ~15 s |
| M | fq-dev, e2e kind | ~3,65 M (+ extrait Open Library) | 1,25 Go | 5 à 8 min |
| L | hors phase 1 | dizaines de millions | — | point d'extension |

## Tests et CI

```bash
make test      # unitaires : agent (52) + corpus (6)
make verify    # + demo-app (Testcontainers) + intégration it/ (11), ~2 min, Docker requis
```

| Niveau | Ce qui est vérifié |
|---|---|
| Unitaires agent | format du tag, échappement face à des valeurs hostiles, résolution du caller (proxies CGLIB/JDK, lambdas), config, normalisation SQL, N+1, Prometheus, serveur HTTP (threads daemon, port occupé), fail-safe, instrumentation réelle sur H2 et HikariCP |
| Unitaires corpus | déterminisme, volumes, distribution déséquilibrée, clés étrangères, mapping Open Library |
| Intégration (`it/`) | Postgres 16 + corpus S + demo-app dans une JVM séparée avec le **vrai** jar : tag dans `pg_stat_statements`, N+1 détecté et témoin non, une seule requête comptée via Hikari, `FQ_ENABLED=false`, fautes injectées sans effet sur les réponses (comparées octet pour octet à un run sans agent), arrêt de la JVM si l'appli échoue au démarrage, jar shadé sans classe étrangère |
| CI | GitHub Actions, Java 21 : unitaires + corpus + intégration à chaque push |

**Docker non standard (colima…)** : le Makefile récupère le socket du contexte Docker actif. Pour lancer
`./mvnw verify` directement :

```bash
export DOCKER_HOST=$(docker context inspect --format '{{.Endpoints.docker.Host}}')
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock   # colima uniquement
```

## Surcoût mesuré

`scripts/bench.sh` lance la même demo-app sur la même base, sans puis avec l'agent. Il mesure la latence
d'un client séquentiel (`curl`), après 200 requêtes de chauffe par endpoint.

Mesures du 2026-11-23 : Mac Intel i7-9750H, JDK 25, PostgreSQL 16 dans colima, profil M, agent avec
`FQ_SAMPLING_RATE=1.0` :

| Endpoint | Requêtes SQL / appel | Requêtes | p50 sans | p50 avec | Δ p50 | p99 sans | p99 avec | Δ p99 |
|---|---|---|---|---|---|---|---|---|
| `/publishers/1` | 1 | 2000 | 5,88 ms | 6,13 ms | **+0,25 ms** | 8,87 ms | 9,46 ms | +0,59 ms |
| `/books?limit=50` | ~45 | 500 | 79,61 ms | 82,97 ms | **+3,36 ms** | 108,41 ms | 114,01 ms | +5,60 ms |
| `/authors?limit=20` | 22 | 200 | 129,32 ms | 132,37 ms | **+3,04 ms** | 182,45 ms | 186,62 ms | +4,17 ms |

Lecture : environ **70 µs par requête SQL** (résolution du caller par `StackWalker`, tag, mesure) et
**~0,25 ms** sur un endpoint à une requête, soit 3 à 4 %. Le coût est proportionnel au nombre de requêtes :
il est le plus élevé précisément sur les endpoints en N+1. Pour le réduire en production, utiliser
`FQ_SAMPLING_RATE`. Ces mesures sont indicatives (un seul client, machine de développement) ; la mesure
sous charge sur kind viendra avec l'e2e.

## Kubernetes (kind) et plugin K9s

⏳ **À venir** (prochaines étapes de la phase 1) :

- `make cluster build deploy seed load` sur **kind**. Postgres 16 avec `pg_stat_statements` et
  `auto_explain`, demo-app avec l'agent injecté par un initContainer (`emptyDir` + `JAVA_TOOL_OPTIONS`),
  annotations Prometheus. Requests/limits volontairement mal dimensionnés, et un overlay où pool Hikari ×
  replicas > `max_connections`. Overlays Kustomize `dev` (`fq-dev`, persistant) et `e2e` (`fq-e2e`, éphémère).
- `make e2e` : déploiement, corpus, trafic, assertions, suppression garantie du namespace.
- `k9s/plugins.yaml` : `Shift-T` pour `/fq/top`, `Shift-N` pour `/fq/nplusone` sur le pod sélectionné.

Prérequis : `brew install kind kubectl`.

## Garanties fail-safe

| Situation | Comportement | Vérifié par |
|---|---|---|
| Exception (même `Error`) dans l'agent | jamais propagée ; le SQL d'origine est envoyé | unitaires + intégration |
| Plus de 100 erreurs internes | disjoncteur : l'agent passe en no-op (`fq_agent_active 0`) | unitaires + intégration |
| Échec d'initialisation | l'application démarre normalement, sans instrumentation | intégration |
| `FQ_ENABLED=false` | aucune transformation, aucun port ouvert | unitaires + intégration |
| Port HTTP occupé, JRE sans `jdk.httpserver` | endpoints indisponibles, application inchangée | unitaires |
| Application qui échoue au démarrage | la JVM s'arrête (tous les threads de l'agent sont daemon) | intégration |
| Mémoire | structures bornées ; statements référencés faiblement | unitaires |
| Conflit de dépendances | jar shadé, seules des classes `io/github/anthonydeseille/flairquery/**` | intégration |

## Limites connues

1. **`pg_stat_statements` ne garde qu'un texte par `queryid`**, celui de la première occurrence : un seul
   tag, donc un seul endpoint et un seul caller par requête normalisée côté base. Exemple mesuré : le
   chargement des éditeurs vient de 3 endpoints, mais il est attribué en totalité au premier tag vu. La
   répartition exacte vient de l'agent (`/fq/top`, `/fq/nplusone`).
2. **Contexte en ThreadLocal** : requêtes asynchrones (`@Async`, `CompletableFuture`, servlet async,
   WebFlux) non attribuées (`endpoint=none`). Les servlets `javax.*` (Spring Boot 2) ne sont pas
   instrumentés.
3. **Requêtes exécutées avant le mapping Spring** (filtres) : l'endpoint est l'URI brute.
4. **`*` interdit dans le tag** : `/actuator/health/**` apparaît comme `/actuator/health/__`.
5. **`prepareCall` non tagué** (syntaxe `{call …}`).
6. **Batch JDBC** : un `executeBatch` compte pour une exécution, quel que soit le nombre de lignes.
7. **Prepared statements côté serveur (pgjdbc)** : chaque combinaison de tag forme une chaîne SQL
   distincte, ce qui fragmente le cache de statements du driver (`preparedStatementCacheQueries`).
8. **Normalisation SQL** proche de celle de `pg_stat_statements` mais pas identique (texte, pas arbre
   syntaxique) ; la phase 2 corrélera par le tag, pas par le texte.
9. **JDK 24+** : ByteBuddy déclenche un avertissement `sun.misc.Unsafe` sur stderr, sans effet. Les entrées
   multi-release de ByteBuddy sont exclues du jar : relocalisées, elles masquaient le ByteBuddy d'Hibernate.
10. **`auto_explain` en dev** (`log_min_duration=0`) produit des logs très volumineux. Un chargement M a
    écrit 14 Go de logs serveur avant correction : le seed désactive `auto_explain` pour sa session, et le
    conteneur local plafonne ses logs.

## Licence des données

L'extrait Open Library est sous licence CC0. Les autres données sont générées.
