# FlairQuery roadmap

## Phase 1 — Capture
Java agent, SQL tagging (endpoint + caller), N+1 detection, metrics, demo on kind, K9s plugin.

## Phase 2 — PostgreSQL collector (done, single service)
- Periodic reads of `pg_stat_statements`, `auto_explain`, `pg_stat_user_tables`, `pg_stats`
- Correlation through the `fq:` tag → endpoint + caller
- History storage (snapshots) to measure before/after gains

Delivered: snapshots and deltas in a dedicated fq-store; exact per-execution attribution from the
`auto_explain` JSON log (matched to `pg_stat_statements` by text, since the logged `query_id` is the next
statement's inside explicit transactions); plan shapes with values masked; value-free `pg_stats`; marks
and before/after comparison; trap scorecard in the e2e (4/7 detected, 3 partial, witness clean).
Inputs for phase 3: the per-endpoint statements and their callers, plans and filtered scans, column
selectivity.

### Multi-service evolution (after phase 2)
Phase 2 targets a single service. Planned evolution for microservices:
- `pg_stat_statements` is instance-wide and keyed by (role, database, `queryid`, toplevel):
  with **one PostgreSQL role per service**, identical SQL from two services stays in two entries
  (exact attribution); a shared role merges them under the first tag seen.
- Collector with several targets (instances / databases); storage already keyed by
  (instance, database, role, service, `queryid`).
- Detect a role shared by several `app=` values and `FQ_APP_NAME` collisions, and warn.
- Service as a first-class dimension in the API, the K9s views and the trap scorecard.
- Cross-service call chains (service A calls B, which runs the N+1) need distributed tracing
  (propagated trace id); out of scope until then.

## Phase 3 — Per-endpoint profile
- Per endpoint: number of statements, SQL time, rows returned, entities loaded vs used
- Useless EAGER detection:
  - static: scan of mappings (`@ManyToOne` / `@OneToOne` EAGER by default)
  - dynamic: instrumentation of association getters → association loaded but never read
- Over-fetching detection (full entities loaded vs serialized fields)
- Detection of `JOIN FETCH` + pagination and `open-in-view=true`

## Phase 4 — LLM analyzer
- Inputs: source code (entity, repository, service), plan, schema, endpoint profile
- Proposals: LAZY + `@EntityGraph` / `JOIN FETCH` per use case, DTO projections,
  `@BatchSize` / `default_batch_fetch_size`, `@Query` rewrites, indexes (Liquibase changeset,
  `CREATE INDEX CONCURRENTLY`)
- Configurable LLM: external API or local (Ollama)
- Mandatory automatic validation before any proposal:
  - strictly identical JSON snapshot of the response
  - fewer statements and less SQL time (integration test)
  - indexes validated with HypoPG; SQL rewrites validated by a shadow run on a replica (result hashes)

## Phase 5 — Merge requests
- Opened through the GitLab/GitHub API, one MR per fix
- Description: problem, proof (before/after), plan, added tests
- No automatic merge or deployment: human review is mandatory

## Phase 6 — Infrastructure right-sizing
- API pods: requests/limits from the real p95, CPU throttling, JVM heap/GC,
  `MaxRAMPercentage` consistent with the memory limit; VPA in recommendation mode as a source
- Database: `work_mem` (from `temp_blks_written`), `shared_buffers`, `effective_cache_size`
- Cross-check: Hikari pool × replicas ≤ `max_connections`
- Always after the code fixes (never size for waste)
- Output: MR on Kustomize/Helm manifests (PostgreSQL on K8s, e.g. CloudNativePG) or Terraform (managed database)
- Progressive rollout (canary) + automatic rollback; never a live change

### "Memory safety net" option (temporary increase)
Keeps the service running while the fix is written, merged and deployed.
- Floor = base configuration: never an automatic decrease below it
- Mandatory ceiling (default ×2, configurable) + compliance with the namespace `ResourceQuota`
- Increase only on real signals: OOMKilled, GC pressure, sustained heap > 85 %
- Increase applied through a rolling restart (the JVM sets its heap at startup;
  `MaxRAMPercentage` × new limit)
- Leak detection: if consumption climbs again after an increase → stop, no further increase, alert
- Goes through Git: commit in an `fq-override` overlay (auto-merge allowed because it is bounded),
  otherwise ArgoCD/Flux reverts the change
- Override tied to the fix's MR: removed automatically once the MR is merged, deployed and consumption
  is back to normal → back to the base configuration
- Expiry: if the fix is not deployed after N days → alert (no abrupt rollback)
- Traceability: every increase = one commit with its justification (metrics + MR link)
- Possible implementation with a driven VPA (`minAllowed` = base, `maxAllowed` = ceiling);
  beware of conflicts with a CPU-based HPA
- Database: only parameters reloadable without a restart (e.g. `work_mem` per role);
  `shared_buffers` and anything requiring a primary restart stays in an MR with human review

## Phase 7 — Kubernetes operator
- `FlairQuery` CRD (target by labels, thresholds, base packages, associated database)
- Admission webhook injecting the agent (replaces the manual initContainer)
- Collector / analyzer as a Deployment and CronJob; results in the CRD `status`
- Fixes deployed through GitOps (ArgoCD/Flux) only

## Cross-cutting principles
- Read-only on production; no live SQL rewriting
- Only exception to automatic changes: the bounded, temporary memory increase,
  traced in Git, never an automatic decrease below the base configuration
- Fail-safe: the tool must never impact the observed application
- Every recommendation is proven (measurement or planner), never merely "suggested by the LLM"
- `demo-app/TRAPS.md` is the ground truth: detection and fix rates measured at every phase
