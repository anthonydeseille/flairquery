-- Executed once at first start (docker-entrypoint-initdb.d / K8s init).
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Read-only role for FlairQuery checks (collector in phase 2): statistics only, no data writes.
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'fq_reader') THEN
    CREATE ROLE fq_reader LOGIN PASSWORD 'fq_reader';
  END IF;
END $$;
GRANT pg_read_all_stats TO fq_reader;
GRANT CONNECT ON DATABASE flairquery TO fq_reader;
