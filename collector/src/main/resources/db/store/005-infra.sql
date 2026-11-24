--liquibase formatted sql

--changeset flairquery:005-infra-sample
-- Infrastructure readings for sizing (phase 6), one row per subject and cycle, stored as read: containers
-- (Kubernetes resources, restarts, cAdvisor usage and throttling), JVMs (agent /fq/jvm) and the observed PostgreSQL
-- server (settings, temporary files, connections). Cumulative counters are kept raw: rates are computed on read.
CREATE TABLE fq_infra_sample (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target    TEXT NOT NULL,
    taken_at  TIMESTAMPTZ NOT NULL,
    kind      TEXT NOT NULL,
    subject   TEXT NOT NULL,
    payload   JSONB NOT NULL
);
CREATE INDEX fq_infra_sample_read ON fq_infra_sample (target, kind, taken_at);
