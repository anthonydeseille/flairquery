--liquibase formatted sql

--changeset flairquery:001-snapshot
-- One collection cycle on one target. Deltas are attached to the snapshot that observed them.
CREATE TABLE fq_snapshot (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target            TEXT        NOT NULL,
    taken_at          TIMESTAMPTZ NOT NULL,
    previous_at       TIMESTAMPTZ,              -- start of the interval covered by the deltas
    baseline          BOOLEAN     NOT NULL,     -- first cycle: cursors only, no delta
    stats_reset       TIMESTAMPTZ,              -- pg_stat_statements_info.stats_reset
    stats_reset_seen  BOOLEAN     NOT NULL DEFAULT FALSE,
    dealloc_total     BIGINT,                   -- pg_stat_statements_info.dealloc (cumulative)
    dealloc_delta     BIGINT      NOT NULL DEFAULT 0, -- entries evicted since the previous snapshot
    statements        INTEGER     NOT NULL DEFAULT 0,
    duration_ms       DOUBLE PRECISION
);
CREATE INDEX fq_snapshot_target_time ON fq_snapshot (target, taken_at);

--changeset flairquery:001-statement
-- Identity of a pg_stat_statements entry. Keyed like pg_stat_statements itself (role, database, queryid,
-- toplevel) plus the target, so multi-service / multi-target evolutions do not change the model.
CREATE TABLE fq_statement (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target      TEXT    NOT NULL,
    dbid        BIGINT  NOT NULL,
    userid      BIGINT  NOT NULL,
    queryid     BIGINT  NOT NULL,
    toplevel    BOOLEAN NOT NULL,
    db_name     TEXT,
    role_name   TEXT,
    query       TEXT,          -- pg_stat_statements text (first occurrence, constants replaced by $n)
    app         TEXT,          -- from the fq: tag of that text (first occurrence only, see phase 2 step 4)
    endpoint    TEXT,
    caller      TEXT,
    first_seen  TIMESTAMPTZ NOT NULL,
    last_seen   TIMESTAMPTZ NOT NULL,
    UNIQUE (target, dbid, userid, queryid, toplevel)
);

--changeset flairquery:001-statement-cursor
-- Last cumulative counters seen, to compute the next delta (survives collector restarts).
CREATE TABLE fq_statement_cursor (
    statement_id        BIGINT PRIMARY KEY REFERENCES fq_statement (id) ON DELETE CASCADE,
    calls               BIGINT NOT NULL,
    total_exec_time     DOUBLE PRECISION NOT NULL,
    rows                BIGINT NOT NULL,
    shared_blks_hit     BIGINT NOT NULL,
    shared_blks_read    BIGINT NOT NULL,
    temp_blks_read      BIGINT NOT NULL,
    temp_blks_written   BIGINT NOT NULL
);

--changeset flairquery:001-statement-delta
CREATE TABLE fq_statement_delta (
    snapshot_id         BIGINT NOT NULL REFERENCES fq_snapshot (id) ON DELETE CASCADE,
    statement_id        BIGINT NOT NULL REFERENCES fq_statement (id) ON DELETE CASCADE,
    calls               BIGINT NOT NULL,
    total_exec_time     DOUBLE PRECISION NOT NULL,
    max_exec_time       DOUBLE PRECISION,       -- cumulative max since reset (not a delta)
    rows                BIGINT NOT NULL,
    shared_blks_hit     BIGINT NOT NULL,
    shared_blks_read    BIGINT NOT NULL,
    temp_blks_read      BIGINT NOT NULL,
    temp_blks_written   BIGINT NOT NULL,
    restarted           BOOLEAN NOT NULL,       -- counters restarted (eviction or reset): delta = full value
    PRIMARY KEY (snapshot_id, statement_id)
);
CREATE INDEX fq_statement_delta_statement ON fq_statement_delta (statement_id);

--changeset flairquery:001-table
CREATE TABLE fq_table (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target      TEXT   NOT NULL,
    db_name     TEXT   NOT NULL,
    relid       BIGINT NOT NULL,
    schema_name TEXT   NOT NULL,
    table_name  TEXT   NOT NULL,
    UNIQUE (target, db_name, relid)
);

CREATE TABLE fq_table_cursor (
    table_id        BIGINT PRIMARY KEY REFERENCES fq_table (id) ON DELETE CASCADE,
    seq_scan        BIGINT NOT NULL,
    seq_tup_read    BIGINT NOT NULL,
    idx_scan        BIGINT NOT NULL,
    idx_tup_fetch   BIGINT NOT NULL,
    n_tup_ins       BIGINT NOT NULL,
    n_tup_upd       BIGINT NOT NULL,
    n_tup_del       BIGINT NOT NULL
);

CREATE TABLE fq_table_delta (
    snapshot_id     BIGINT NOT NULL REFERENCES fq_snapshot (id) ON DELETE CASCADE,
    table_id        BIGINT NOT NULL REFERENCES fq_table (id) ON DELETE CASCADE,
    seq_scan        BIGINT NOT NULL,
    seq_tup_read    BIGINT NOT NULL,
    idx_scan        BIGINT NOT NULL,
    idx_tup_fetch   BIGINT NOT NULL,
    n_tup_ins       BIGINT NOT NULL,
    n_tup_upd       BIGINT NOT NULL,
    n_tup_del       BIGINT NOT NULL,
    n_live_tup      BIGINT,                     -- gauge, not a delta
    restarted       BOOLEAN NOT NULL,
    PRIMARY KEY (snapshot_id, table_id)
);
