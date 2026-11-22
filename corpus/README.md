# Corpus de démo

Données chargées **uniquement par `COPY`** (jamais par Liquibase, qui ne gère que le schéma).
Rien n'est écrit sur disque côté client : `generate.py` produit du CSV sur stdout, envoyé directement
à `psql \copy` (ou au `CopyManager` JDBC dans les tests d'intégration).

```bash
make seed-local PROFILE=S            # local (Postgres de make demo-local)
make seed-local PROFILE=M FORCE=1    # recharge en tronquant
make openlibrary                     # (optionnel) extrait Open Library pour le profil M
python3 corpus/generate.py --profile S --expect   # faits attendus (tests)
```

## Profils

| Profil | Usage | publisher | author | book | review | Total | Taille disque | Chargement (local, colima) |
|---|---|---|---|---|---|---|---|---|
| **S** | Testcontainers, CI | 500 | 5 000 | 23 485 | 70 000 | ~99 k lignes | **41 Mo** | ~15 s |
| **M** | fq-dev, e2e kind | 5 000 | 200 000 | 440 167 | 3 000 000 | ~3,65 M lignes | **1,25 Go** | 5 à 8 min |
| M + Open Library | fq-dev | + extrait | + extrait | + extrait | + extrait | | + quelques Mo | + quelques s |
| **L** | hors phase 1 | — | — | — | — | dizaines de millions | — | point d'extension uniquement (`generate.py` échoue explicitement) |

Mesures du 2026-11-22 sur un Mac (Postgres 16 dans colima, `shared_buffers=32MB`). Le temps est dominé
par l'écriture Postgres (TOAST des descriptions, index, clés étrangères), pas par la génération
(27 s pour les 440 k livres de M).

Pourquoi ces volumes : M dépasse largement `shared_buffers` (32 Mo) et `work_mem` (64 kB), ce qui force
les lectures disque et les tris sur disque (piège 7). S suffit pour tous les pièges en CI.

## Forme des données générées (déterministe, seed 42)

- **Distribution très déséquilibrée** : quelques auteurs « prolifiques » ont exactement 5 000 livres
  (S : ids 7, 1673, 3339), tous les autres 1 à 3 livres. L'auteur 7 est toujours dans la première page
  de `GET /authors`.
- `book.description` : texte long (~400-900 caractères) → le sur-fetch (piège 3) se voit.
- `review` : plus grosse table, `reviewer_name` **non indexé** (piège 4), tri sur `created_at, comment`
  non indexés (piège 7). Reviewer connu : `Alice Martin 00000`.
- Même profil ⇒ même sortie octet pour octet (testé). Les assertions d'intégration et d'e2e reposent
  sur ces données générées, **jamais** sur l'extrait Open Library.

## Extrait Open Library (CC0)

`corpus/openlibrary/fetch.py` lit les dumps mensuels **en flux HTTP** (works ~3 Go, editions ~10 Go
compressés), décompresse et filtre à la volée, et ne met en cache que le sous-ensemble retenu
(`~/.cache/flairquery/openlibrary/subset.jsonl.gz`, quelques Mo, hors du repo). Chaque passe a un
plafond (`--works-scan-mb`, `--editions-scan-mb`...) pour borner temps et bande passante : l'extrait
est un échantillon.

| Open Library | → | Table |
|---|---|---|
| authors | → | `author` (`ol_key` conservé) |
| works (+ une edition) | → | `book` (ISBN, année, éditeur pris dans l'edition) |
| publishers des editions | → | `publisher` |
| ratings | → | `review` (`reviewer_name = openlibrary-user`) |

Les ids Open Library commencent à 50 000 000 : aucune collision avec les données générées.
`load.sh` charge l'extrait pour le profil M s'il est en cache (`FQ_OPENLIBRARY=auto|off|required`).

## Point d'extension profil L

`PROFILES["L"] = None` dans `generate.py` : la commande échoue explicitement. L'implémentation prévue :
extrait Open Library complet (sans plafond de scan) + reviews générées à grande échelle, chargement
parallèle par table.

## Points d'attention constatés

- **auto_explain pendant le seed** : avec `log_min_duration=0` et `log_nested_statements=on`, chaque
  vérification de clé étrangère déclenchée par le `COPY` est journalisée. Un chargement M a produit un log
  serveur de **14 Go**. `load.sh` désactive donc `auto_explain` pour sa session (paramètre superuser), et
  le conteneur local plafonne ses logs (2 × 50 Mo).
