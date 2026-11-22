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
LOCAL_COMPOSE := deploy/local/compose.yaml
LOCAL_PG := PGHOST=localhost PGPORT=5432 PGUSER=flairquery PGPASSWORD=flairquery PGDATABASE=flairquery
FQ_LOCAL_ENV := FQ_APP_NAME=demo-app FQ_BASE_PACKAGES=io.github.anthonydeseille.flairquery.demo

.PHONY: help
help: ## List targets
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

# ---------------------------------------------------------------- build & tests
.PHONY: jars test verify
jars: ## Build the agent (shaded) and demo app jars, without tests
	./mvnw -B -q package -DskipTests

test: ## Unit tests
	./mvnw -B test

verify: ## Unit + integration tests (Testcontainers, needs Docker)
	./mvnw -B verify

# ---------------------------------------------------------------- local playground (no Kubernetes)
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
