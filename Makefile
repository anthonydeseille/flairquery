# FlairQuery — phase 1
SHELL := /bin/bash
.DEFAULT_GOAL := help

PROFILE ?= S

# Testcontainers with a non-default Docker socket (colima, Docker Desktop contexts...):
# use the endpoint of the active docker context; colima also needs the in-VM socket for Ryuk.
DOCKER_HOST ?= $(shell docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null)
export DOCKER_HOST
ifneq (,$(findstring colima,$(DOCKER_HOST)))
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE ?= /var/run/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE
endif
AGENT_JAR := agent/target/flairquery-agent.jar
DEMO_JAR  := demo-app/target/flairquery-demo-app.jar
COLLECTOR_JAR := collector/target/flairquery-collector.jar
LOCAL_COMPOSE := deploy/local/compose.yaml
LOCAL_PG := PGHOST=localhost PGPORT=5432 PGUSER=flairquery PGPASSWORD=flairquery PGDATABASE=flairquery
FQ_LOCAL_ENV := FQ_APP_NAME=demo-app FQ_BASE_PACKAGES=io.github.anthonydeseille.flairquery.demo

.PHONY: help
help: ## List targets
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

# ---------------------------------------------------------------- build & tests
.PHONY: jars test verify
jars: ## Build the agent (shaded), collector and demo app jars, without tests
	./mvnw -B -q package -DskipTests

test: ## Unit tests (Java + corpus)
	./mvnw -B test
	python3 -m unittest discover -s corpus -p 'test_*.py'

verify: ## Unit + integration tests (Testcontainers, needs Docker); LlmFixIT is skipped (make llm-it)
	./mvnw -B verify

.PHONY: llm-it
llm-it: ## LLM fixes of traps 1, 3, 5, 6 proven end to end (real Claude API calls, billed; needs ANTHROPIC_API_KEY + Docker)
	@test -n "$$ANTHROPIC_API_KEY" || { echo "ANTHROPIC_API_KEY is not set"; exit 2; }
	./mvnw -B -q -pl core,agent -am install -DskipTests
	./mvnw -B -pl analyzer verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=LlmFixIT -Dfq.llm.it=true

# ---------------------------------------------------------------- local playground (no Kubernetes)
.PHONY: db-grants-local
db-grants-local: ## (Re)apply the fq_reader role and grants on the local Compose database (idempotent)
	docker compose -f $(LOCAL_COMPOSE) exec -T postgres psql -U flairquery -d flairquery -v ON_ERROR_STOP=1 -q < deploy/postgres/init-fq.sql

.PHONY: mark
mark: ## Name the current moment on the local collector (NAME=before-fix [NOTE=...]); runs a cycle now
	@test -n "$(NAME)" || { echo "usage: make mark NAME=before-fix [NOTE=...]"; exit 2; }
	@jq -n --arg name "$(NAME)" --arg note "$(NOTE)" '{name: $$name} + (if $$note == "" then {} else {note: $$note} end)' \
	  | curl -sS -X POST -H 'Content-Type: application/json' --data-binary @- localhost:$(or $(COLLECTOR_PORT),9465)/fq/collector/marks | jq

.PHONY: compare
compare: ## Compare two periods on the local collector (BEFORE=<mark|from..to> AFTER=<mark|from..to> [LIMIT=10])
	@test -n "$(BEFORE)" -a -n "$(AFTER)" || { echo "usage: make compare BEFORE=before AFTER=fix"; exit 2; }
	@curl -sS -G localhost:$(or $(COLLECTOR_PORT),9465)/fq/collector/compare --data-urlencode "before=$(BEFORE)" \
	  --data-urlencode "after=$(AFTER)" --data-urlencode "limit=$(or $(LIMIT),10)" \
	  | jq '{before, after, totals, notes, endpoints: [.endpoints[]? | {endpoint, before, after, changePct}], \
	         statements: [.statements[]? | {queryid, status, endpoints, sql: .sql[0:100], before, after, \
	         changePct: {totalTimeMs: .changePct.totalTimeMs, calls: .changePct.calls}, planChanged: .plans.changed}]}'

.PHONY: scorecard-local
scorecard-local: ## Trap scorecard from the local collector (WINDOW=1h); run make traffic-local first
	python3 scripts/scorecard.py --url http://localhost:$(or $(COLLECTOR_PORT),9465) --window $(or $(WINDOW),1h)

.PHONY: analyze-local
analyze-local: ## Evidence bundle per finding (phase 4) from the local agent + collector -> target/analysis
	java -jar analyzer/target/flairquery-analyzer.jar bundle --agent http://localhost:9464 \
	  --collector http://localhost:$(or $(COLLECTOR_PORT),9465) --source demo-app --out target/analysis --window $(or $(WINDOW),1h)

.PHONY: prove-local
prove-local: ## Prove patches for one finding (FINDING=<id> PATCH=a.patch[,b.patch] [ROUNDS=5]); needs Docker -> target/proofs
	@test -n "$(FINDING)" -a -n "$(PATCH)" || { echo "usage: make prove-local FINDING=<id from target/analysis/index.json> PATCH=fix.patch"; exit 2; }
	./mvnw -B -q -pl core,agent,analyzer -am package -DskipTests
	java -jar analyzer/target/flairquery-analyzer.jar prove --finding $(FINDING) --patch $(PATCH) --rounds $(or $(ROUNDS),5)

.PHONY: fix-local
fix-local: ## Generate fixes from target/analysis (templates, then Claude) and prove them ([FINDING=id,id] [ROUNDS=5] [LLM=off] [RETRIES=2]); needs Docker + ANTHROPIC_API_KEY -> target/fixes
	./mvnw -B -q -pl core,agent,analyzer -am package -DskipTests
	java -jar analyzer/target/flairquery-analyzer.jar fix --bundles target/analysis --rounds $(or $(ROUNDS),5) \
	  --llm $(or $(LLM),claude) --llm-retries $(or $(RETRIES),2) $(if $(MODEL),--model $(MODEL)) \
	  $(if $(EFFORT),--effort $(EFFORT)) $(if $(FINDING),--finding $(FINDING))

.PHONY: openlibrary
openlibrary: ## Stream Open Library dumps and cache a small subset (~/.cache/flairquery/openlibrary)
	python3 -I corpus/openlibrary/fetch.py $(OL_ARGS)

.PHONY: db-restart-local collector-local
db-restart-local: ## Restart the local PostgreSQL (applies a changed deploy/postgres/postgresql-fq.conf)
	docker compose -f $(LOCAL_COMPOSE) restart postgres
	docker compose -f $(LOCAL_COMPOSE) up -d --wait

collector-local: jars ## Collector on the host against the local Compose databases (foreground, Ctrl-C to stop)
	docker compose -f $(LOCAL_COMPOSE) up -d --wait
	$(MAKE) --no-print-directory db-grants-local
	FQ_COLLECTOR_TARGET_URL=jdbc:postgresql://localhost:5432/flairquery FQ_COLLECTOR_TARGET_NAME=local \
	FQ_COLLECTOR_STORE_URL=jdbc:postgresql://localhost:5433/fqstore FQ_COLLECTOR_INTERVAL=$(or $(INTERVAL),30) \
	FQ_COLLECTOR_AGENT_URL=$(or $(AGENT_URL),http://localhost:9464) \
	  java -jar collector/target/flairquery-collector.jar

.PHONY: demo-local seed-local traffic-local demo-local-clean
demo-local: jars ## Postgres 16 in Docker + demo app with the agent (foreground, Ctrl-C to stop)
	docker compose -f $(LOCAL_COMPOSE) up -d --wait
	$(FQ_LOCAL_ENV) java -javaagent:$(AGENT_JAR) -jar $(DEMO_JAR)

seed-local: ## Load corpus PROFILE=S|M into the local Postgres (demo app must have started once)
	$(LOCAL_PG) corpus/load.sh $(PROFILE) $(if $(FORCE),--force,)

traffic-local: ## Call every demo endpoint (ROUNDS=5)
	scripts/traffic.sh http://localhost:8080 $(or $(ROUNDS),5)

demo-local-clean: ## Stop local Postgres and DELETE its volume
	docker compose -f $(LOCAL_COMPOSE) down -v

# ---------------------------------------------------------------- Kubernetes (kind by default, k3d supported)
CLUSTER_TOOL ?= kind
CLUSTER_NAME := flairquery
KIND    ?= $(if $(wildcard .tools/bin/kind),.tools/bin/kind,kind)
KUBECTL_BIN ?= $(if $(wildcard .tools/bin/kubectl),.tools/bin/kubectl,kubectl)
K3D     ?= k3d
# always target the FlairQuery cluster explicitly, never the current kubectl context
KUBE_CONTEXT := $(if $(filter k3d,$(CLUSTER_TOOL)),k3d-$(CLUSTER_NAME),kind-$(CLUSTER_NAME))
KUBECTL := $(KUBECTL_BIN) --context $(KUBE_CONTEXT)
OVERLAY ?= dev
NS ?= $(if $(filter e2e,$(OVERLAY)),fq-e2e,fq-dev)
IMAGES := flairquery/agent:dev flairquery/demo-app:dev flairquery/collector:dev flairquery/tools:dev
OL_SUBSET ?= $(HOME)/.cache/flairquery/openlibrary/subset.jsonl.gz
ROUNDS ?= 5
DOCKER_CTX := target/docker
DOCKER_ARCH ?= $(shell docker version --format '{{.Server.Arch}}' 2>/dev/null || echo amd64)

.PHONY: tools
tools: ## Download pinned kind + kubectl into .tools/bin (checksums verified, no sudo)
	scripts/install-tools.sh

.PHONY: db-grants
db-grants: ## (Re)apply the fq_reader role and grants on an existing cluster database (idempotent)
	@# retried: on a fresh volume the postgres image restarts the server once after its init scripts, and the
	@# readiness probe can already pass on the temporary init server
	@for i in $$(seq 1 20); do \
	  $(KUBECTL) -n $(NS) exec -i deploy/postgres -- psql -U flairquery -d flairquery -v ON_ERROR_STOP=1 -q \
	    < deploy/postgres/init-fq.sql && exit 0; \
	  echo "db-grants: PostgreSQL not ready yet, retrying ($$i/20)"; sleep 3; \
	done; exit 1

.PHONY: scorecard collector-port-forward
scorecard: ## Trap scorecard from the cluster collector (NS, WINDOW=1h); run make load first
	@port=$$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])'); \
	$(KUBECTL) -n $(NS) port-forward svc/fq-collector $$port:9465 >/dev/null 2>&1 & pf=$$!; \
	trap "kill $$pf 2>/dev/null" EXIT; \
	for i in $$(seq 1 50); do curl -sf -m 1 localhost:$$port/fq/collector/health >/dev/null && break; sleep 0.3; done; \
	python3 scripts/scorecard.py --url http://localhost:$$port --window $(or $(WINDOW),1h)

collector-port-forward: ## Expose the cluster collector on localhost:9465 (then make mark / compare / scorecard-local)
	$(KUBECTL) -n $(NS) port-forward svc/fq-collector 9465:9465

.PHONY: e2e
e2e: ## End-to-end test in the ephemeral fq-e2e namespace (E2E_PROFILE=M|S, E2E_SKIP_BUILD=1, E2E_KEEP=1)
	KUBE_CONTEXT=$(KUBE_CONTEXT) KUBECTL="$(KUBECTL_BIN)" scripts/e2e.sh

.PHONY: cluster build images deploy seed load status undeploy clean
cluster: ## Create the local cluster (kind by default, CLUSTER_TOOL=k3d supported)
ifeq ($(CLUSTER_TOOL),k3d)
	@$(K3D) cluster list $(CLUSTER_NAME) >/dev/null 2>&1 || $(K3D) cluster create $(CLUSTER_NAME) --wait
else
	@$(KIND) get clusters 2>/dev/null | grep -qx $(CLUSTER_NAME) || $(KIND) create cluster --config deploy/kind/cluster.yaml --wait 120s
endif
	$(KUBECTL) get nodes

images: jars ## Build the agent, demo app, collector and tools images
	rm -rf $(DOCKER_CTX) && mkdir -p $(DOCKER_CTX)/agent $(DOCKER_CTX)/demo-app $(DOCKER_CTX)/collector $(DOCKER_CTX)/tools/ol-cache
	cp $(AGENT_JAR) $(DOCKER_CTX)/agent/ && cp $(DEMO_JAR) $(DOCKER_CTX)/demo-app/ && cp $(COLLECTOR_JAR) $(DOCKER_CTX)/collector/
	cp -R corpus scripts $(DOCKER_CTX)/tools/ && rm -rf $(DOCKER_CTX)/tools/corpus/__pycache__
	@if [ -f "$(OL_SUBSET)" ]; then cp "$(OL_SUBSET)" $(DOCKER_CTX)/tools/ol-cache/ && echo "Open Library subset included"; else echo "no Open Library subset cached (generated data only)"; fi
	docker build -q -t flairquery/agent:dev -f deploy/images/agent/Dockerfile $(DOCKER_CTX)/agent
	docker build -q -t flairquery/demo-app:dev -f deploy/images/demo-app/Dockerfile $(DOCKER_CTX)/demo-app
	docker build -q -t flairquery/collector:dev -f deploy/images/collector/Dockerfile $(DOCKER_CTX)/collector
	docker build -q -t flairquery/tools:dev -f deploy/images/tools/Dockerfile $(DOCKER_CTX)/tools

build: images ## Build images and load them into the cluster
	docker image inspect postgres:16 >/dev/null 2>&1 || docker pull -q postgres:16
ifeq ($(CLUSTER_TOOL),k3d)
	$(K3D) image import -c $(CLUSTER_NAME) $(IMAGES) postgres:16
else
	@# `kind load docker-image` fails on multi-platform images with Docker's containerd image store
	@# ("content digest ... not found"): fall back to a single-platform archive (deleted right after).
	@mkdir -p $(DOCKER_CTX) && for img in $(IMAGES) postgres:16; do \
	  if $(KIND) load docker-image --name $(CLUSTER_NAME) $$img >/dev/null 2>&1; then echo "loaded $$img"; else \
	  docker save --platform linux/$(DOCKER_ARCH) -o $(DOCKER_CTX)/img.tar $$img && \
	  $(KIND) load image-archive --name $(CLUSTER_NAME) $(DOCKER_CTX)/img.tar >/dev/null && \
	  rm -f $(DOCKER_CTX)/img.tar && echo "loaded $$img (single-platform archive)"; fi; done
endif

deploy: ## Deploy OVERLAY=dev|e2e|pool-overflow and wait until ready
	$(KUBECTL_BIN) kustomize --load-restrictor=LoadRestrictionsNone deploy/k8s/overlays/$(OVERLAY) | $(KUBECTL) apply -f -
	$(KUBECTL) -n $(NS) rollout status deploy/postgres --timeout=5m
	@# init-fq.sql only runs on a fresh volume: re-apply the (idempotent) fq_reader grants on existing data
	$(MAKE) --no-print-directory db-grants NS=$(NS) OVERLAY=$(OVERLAY)
	$(KUBECTL) -n $(NS) rollout status deploy/demo-app --timeout=10m
	$(KUBECTL) -n $(NS) rollout status deploy/fq-collector --timeout=5m

seed: ## Load corpus PROFILE=S|M with a Job (FORCE=1 to reload)
	-$(KUBECTL) -n $(NS) delete job fq-seed --ignore-not-found --wait=true
	sed -e 's/__PROFILE__/$(PROFILE)/' -e 's/__FORCE__/$(if $(FORCE),--force,none)/' deploy/k8s/jobs/seed.yaml | $(KUBECTL) -n $(NS) apply -f -
	KUBECTL="$(KUBECTL)" scripts/wait-job.sh $(NS) fq-seed 2400
	$(KUBECTL) -n $(NS) logs job/fq-seed --tail=8

load: ## Generate traffic on every demo endpoint with a Job (ROUNDS=5)
	-$(KUBECTL) -n $(NS) delete job fq-load --ignore-not-found --wait=true
	sed -e 's/__ROUNDS__/$(ROUNDS)/' deploy/k8s/jobs/load.yaml | $(KUBECTL) -n $(NS) apply -f -
	KUBECTL="$(KUBECTL)" scripts/wait-job.sh $(NS) fq-load 1800
	$(KUBECTL) -n $(NS) logs job/fq-load --tail=3

status: ## Pods and resources of the namespace
	$(KUBECTL) -n $(NS) get pods,svc,pvc,jobs -o wide

undeploy: ## Delete the namespace NS (and its data)
	$(KUBECTL) delete namespace $(NS) --ignore-not-found --wait=true

clean: ## Delete the cluster and the FlairQuery images (frees disk space)
ifeq ($(CLUSTER_TOOL),k3d)
	-$(K3D) cluster delete $(CLUSTER_NAME)
else
	-$(KIND) delete cluster --name $(CLUSTER_NAME)
endif
	-docker image rm $(IMAGES) 2>/dev/null
	rm -rf $(DOCKER_CTX)

# ---------------------------------------------------------------- K9s plugin
K9S_PLUGINS ?= $(shell k9s info 2>/dev/null | sed 's/\x1b\[[0-9;]*m//g' | sed -n 's/^Plugins: *//p')
.PHONY: k9s-plugin
k9s-plugin: ## Install the K9s plugins (Shift-Q top SQL, Shift-W N+1, Shift-E endpoints) on the pods view
	@test -n "$(K9S_PLUGINS)" || { echo "k9s not found (k9s info)"; exit 1; }
	@mkdir -p "$$(dirname "$(K9S_PLUGINS)")"
	@if [ -f "$(K9S_PLUGINS)" ] && ! grep -q 'flairquery-top' "$(K9S_PLUGINS)"; then echo "$(K9S_PLUGINS) already exists: add the entries of k9s/plugins.yaml to it, replacing __FQ_VIEW__ by $(CURDIR)/k9s/fq-view.sh"; exit 1; fi
	sed 's#__FQ_VIEW__#$(CURDIR)/k9s/fq-view.sh#g' k9s/plugins.yaml > "$(K9S_PLUGINS)"
	@echo "installed: $(K9S_PLUGINS)"
