--liquibase formatted sql

--changeset flairquery:004-endpoint
-- HTTP endpoints as counted by the FlairQuery agent (/fq/endpoints): gives the number of requests behind
-- the database activity, so before/after comparisons can be made per request.
CREATE TABLE fq_endpoint (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target    TEXT NOT NULL,
    app       TEXT NOT NULL,
    endpoint  TEXT NOT NULL,
    UNIQUE (target, app, endpoint)
);

CREATE TABLE fq_endpoint_cursor (
    endpoint_id BIGINT PRIMARY KEY REFERENCES fq_endpoint (id) ON DELETE CASCADE,
    requests    BIGINT NOT NULL,
    queries     BIGINT NOT NULL,
    sql_ms      DOUBLE PRECISION NOT NULL,
    http_ms     DOUBLE PRECISION NOT NULL
);

CREATE TABLE fq_endpoint_delta (
    snapshot_id BIGINT NOT NULL REFERENCES fq_snapshot (id) ON DELETE CASCADE,
    endpoint_id BIGINT NOT NULL REFERENCES fq_endpoint (id) ON DELETE CASCADE,
    requests    BIGINT NOT NULL,
    queries     BIGINT NOT NULL,
    sql_ms      DOUBLE PRECISION NOT NULL,
    http_ms     DOUBLE PRECISION NOT NULL,
    restarted   BOOLEAN NOT NULL DEFAULT FALSE,   -- agent restarted (counters went down): full value taken
    PRIMARY KEY (snapshot_id, endpoint_id)
);

--changeset flairquery:004-snapshot-agent
ALTER TABLE fq_snapshot ADD COLUMN agent_read BOOLEAN;   -- null: no agent configured
