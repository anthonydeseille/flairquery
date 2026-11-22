# Roadmap FlairQuery

## Phase 1 — Captation
Agent Java, tagging SQL (endpoint + caller), détection N+1, métriques, démo sur kind, plugin K9s.

## Phase 2 — Collector Postgres
- Lecture périodique de `pg_stat_statements`, `auto_explain`, `pg_stat_user_tables`, `pg_stats`
- Corrélation via le tag `fq:` → endpoint + caller
- Stockage de l'historique (snapshots) pour mesurer les gains avant/après

## Phase 3 — Profil par endpoint
- Par endpoint : nb de requêtes, temps SQL, lignes ramenées, entités chargées vs utilisées
- Détection EAGER inutile :
  - statique : scan des mappings (`@ManyToOne` / `@OneToOne` EAGER par défaut)
  - dynamique : instrumentation des getters d'associations → association chargée mais jamais lue
- Détection sur-fetch (entités complètes chargées vs champs sérialisés)
- Détection `JOIN FETCH` + pagination, `open-in-view=true`

## Phase 4 — Analyzer LLM
- Entrées : code source (entité, repository, service), plan, schéma, profil endpoint
- Propositions : LAZY + `@EntityGraph` / `JOIN FETCH` par cas d'usage, projections DTO,
  `@BatchSize` / `default_batch_fetch_size`, réécriture `@Query`, index (changeset Liquibase,
  `CREATE INDEX CONCURRENTLY`)
- LLM configurable : API externe ou local (Ollama)
- Validation automatique obligatoire avant toute proposition :
  - snapshot JSON de la réponse strictement identique
  - nombre de requêtes et temps SQL en baisse (test d'intégration)
  - index validés par HypoPG ; réécritures SQL validées en shadow-run sur réplica (hash des résultats)

## Phase 5 — Merge requests
- Ouverture via API GitLab/GitHub, une MR par correctif
- Description : problème, preuve (avant/après), plan, tests ajoutés
- Aucun merge ni déploiement automatique : revue humaine obligatoire

## Phase 6 — Redimensionnement infra
- Pods API : requests/limits depuis le p95 réel, throttling CPU, heap/GC JVM,
  `MaxRAMPercentage` cohérent avec la limite mémoire ; VPA en mode recommendation comme source
- Base : `work_mem` (via `temp_blks_written`), `shared_buffers`, `effective_cache_size`
- Contrôle croisé : pool Hikari × replicas ≤ `max_connections`
- Toujours après les correctifs de code (ne pas dimensionner du gaspillage)
- Sortie : MR sur manifestes Kustomize/Helm (Postgres sur K8s, ex. CloudNativePG) ou Terraform (base managée)
- Déploiement progressif (canary) + rollback auto ; jamais de modification à chaud

### Option « filet de sécurité mémoire » (hausse temporaire)
Permet de tenir en attendant que le correctif soit codé, mergé et déployé.
- Plancher = config de base : jamais de baisse automatique en dessous
- Plafond obligatoire (défaut ×2, configurable) + respect du `ResourceQuota` du namespace
- Hausse uniquement sur signaux réels : OOMKilled, pression GC, heap > 85 % soutenu
- Hausse appliquée par rolling restart (la JVM fixe son heap au démarrage ;
  `MaxRAMPercentage` × nouvelle limite)
- Détection de fuite : si la consommation remonte après une hausse → stop, plus de hausse, alerte
- Passe par Git : commit dans un overlay `fq-override` (merge auto autorisé car borné),
  sinon ArgoCD/Flux annule la modification
- Override lié à la MR du correctif : retiré automatiquement une fois la MR mergée, déployée
  et la consommation revenue à la normale → retour à la config de base
- Expiration : si le correctif n'est pas déployé après N jours → alerte (pas de rollback brutal)
- Traçabilité : chaque hausse = un commit avec justification (métriques + lien MR)
- Implémentation possible via un VPA piloté (`minAllowed` = base, `maxAllowed` = plafond) ;
  attention aux conflits avec un HPA sur CPU
- Base de données : uniquement les paramètres rechargeables sans restart (ex. `work_mem` par rôle) ;
  `shared_buffers` et tout ce qui impose un restart du primaire reste en MR avec revue humaine

## Phase 7 — Opérateur Kubernetes
- CRD `FlairQuery` (cible par labels, seuils, base-packages, base associée)
- Webhook d'admission qui injecte l'agent (remplace l'initContainer manuel)
- Collector / analyzer en Deployment et CronJob ; résultats dans le `status` de la CRD
- Déploiement des correctifs via GitOps (ArgoCD/Flux) uniquement

## Principes transverses
- Lecture seule sur la prod ; aucune réécriture SQL à chaud
- Seule exception aux modifications automatiques : la hausse mémoire bornée, temporaire,
  tracée dans Git, jamais de baisse automatique sous la config de base
- Fail-safe : l'outil ne doit jamais impacter l'application observée
- Chaque recommandation est prouvée (mesure ou planner), jamais seulement « suggérée par le LLM »
- `demo-app/TRAPS.md` sert de vérité terrain : taux de détection et de correction mesuré à chaque phase
