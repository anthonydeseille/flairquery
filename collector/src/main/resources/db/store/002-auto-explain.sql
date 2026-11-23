--liquibase formatted sql

--changeset flairquery:002-log-cursor
-- Read position in each server log file (files are reused every day: `head` detects a new content).
CREATE TABLE fq_log_cursor (
    target      TEXT   NOT NULL,
    file_name   TEXT   NOT NULL,
    read_offset BIGINT NOT NULL,
    head        TEXT,
    updated_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (target, file_name)
);

--changeset flairquery:002-attribution
-- Exact attribution from auto_explain: every logged execution carries its query_id AND its own fq: tag.
CREATE TABLE fq_attribution (
    snapshot_id   BIGINT NOT NULL REFERENCES fq_snapshot (id) ON DELETE CASCADE,
    db_name       TEXT   NOT NULL,
    role_name     TEXT   NOT NULL,
    queryid       BIGINT NOT NULL,
    app           TEXT,
    endpoint      TEXT,
    caller        TEXT,
    executions    BIGINT NOT NULL,
    total_ms      DOUBLE PRECISION NOT NULL,
    max_ms        DOUBLE PRECISION NOT NULL
);
CREATE INDEX fq_attribution_snapshot ON fq_attribution (snapshot_id);
CREATE INDEX fq_attribution_query ON fq_attribution (db_name, role_name, queryid);

--changeset flairquery:002-plan
-- Distinct plan shapes per statement (fingerprint = node tree without costs), with an example plan.
CREATE TABLE fq_plan (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target       TEXT   NOT NULL,
    db_name      TEXT   NOT NULL,
    queryid      BIGINT NOT NULL,
    fingerprint  TEXT   NOT NULL,
    summary      JSONB  NOT NULL,
    plan         JSONB  NOT NULL,
    executions   BIGINT NOT NULL,
    first_seen   TIMESTAMPTZ NOT NULL,
    last_seen    TIMESTAMPTZ NOT NULL,
    UNIQUE (target, db_name, queryid, fingerprint)
);

--changeset flairquery:002-snapshot-log
ALTER TABLE fq_snapshot ADD COLUMN log_entries INTEGER NOT NULL DEFAULT 0;
ALTER TABLE fq_snapshot ADD COLUMN log_lag_bytes BIGINT NOT NULL DEFAULT 0;
ALTER TABLE fq_snapshot ADD COLUMN log_unparsable INTEGER NOT NULL DEFAULT 0;
