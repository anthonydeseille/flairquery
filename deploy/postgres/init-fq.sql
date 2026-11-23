-- FlairQuery read-only role. Executed at first start (docker-entrypoint-initdb.d) and idempotent, so it
-- can be re-applied to an existing database (make db-grants / make db-grants-local).
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'fq_reader') THEN
    CREATE ROLE fq_reader LOGIN PASSWORD 'fq_reader';
  END IF;
END $$;

-- statistics views + pg_ls_logdir()
GRANT pg_monitor TO fq_reader;
-- server log files (log_directory only: absolute paths and files outside the data directory are refused)
GRANT EXECUTE ON FUNCTION pg_read_file(text, bigint, bigint) TO fq_reader;
-- binary variant: incremental reads may cut a multi-byte character, the collector splits on line ends
GRANT EXECUTE ON FUNCTION pg_read_binary_file(text, bigint, bigint) TO fq_reader;
GRANT EXECUTE ON FUNCTION pg_stat_file(text) TO fq_reader;
GRANT CONNECT ON DATABASE flairquery TO fq_reader;

-- pg_stats only shows columns of tables the role can SELECT. The collector reads value-free statistics
-- only (n_distinct, null_frac, correlation, avg_width) and never queries application tables.
GRANT USAGE ON SCHEMA public TO fq_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO fq_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE flairquery IN SCHEMA public GRANT SELECT ON TABLES TO fq_reader;
