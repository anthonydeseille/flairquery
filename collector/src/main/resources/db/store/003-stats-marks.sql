--liquibase formatted sql

--changeset flairquery:003-purge-unmasked-plans
-- Plans stored before value masking may contain parameter values printed by auto_explain: dropped.
DELETE FROM fq_plan;

--changeset flairquery:003-plan-execution
-- Plan shapes per snapshot: needed to tell which plan ran in a given period (before/after comparisons).
CREATE TABLE fq_plan_execution (
    snapshot_id BIGINT NOT NULL REFERENCES fq_snapshot (id) ON DELETE CASCADE,
    plan_id     BIGINT NOT NULL REFERENCES fq_plan (id) ON DELETE CASCADE,
    executions  BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id, plan_id)
);
CREATE INDEX fq_plan_execution_plan ON fq_plan_execution (plan_id);

--changeset flairquery:003-column-stats
-- Value-free planner statistics (pg_stats) of the tables seen in plans. Never most_common_vals,
-- histogram_bounds or any other column that holds data values. Latest reading only.
CREATE TABLE fq_column_stats (
    target       TEXT NOT NULL,
    db_name      TEXT NOT NULL,
    schema_name  TEXT NOT NULL,
    table_name   TEXT NOT NULL,
    column_name  TEXT NOT NULL,
    n_distinct   REAL,             -- > 0: distinct values; < 0: -fraction of the rows
    null_frac    REAL,
    correlation  REAL,             -- physical order vs column order (-1..1)
    avg_width    INTEGER,
    reltuples    REAL,             -- pg_class.reltuples when read (turns a negative n_distinct into a count)
    collected_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (target, db_name, schema_name, table_name, column_name)
);

--changeset flairquery:003-mark
-- Named points in time (e.g. "before-fix"), each one aligned on a collection cycle.
CREATE TABLE fq_mark (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target      TEXT NOT NULL,
    name        TEXT NOT NULL,
    note        TEXT,
    taken_at    TIMESTAMPTZ NOT NULL,
    snapshot_id BIGINT,            -- the cycle run when the mark was set (kept even after retention purge)
    UNIQUE (target, name)
);
