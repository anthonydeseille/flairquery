# Tools image for in-cluster Jobs: corpus seeding (python3 + psql, COPY streamed, no CSV on disk)
# and traffic generation (bash + curl). Based on postgres:16 so its layers are shared with the database.
FROM postgres:16
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*
COPY corpus/generate.py corpus/load.sh /opt/flairquery/corpus/
COPY corpus/openlibrary/to_csv.py /opt/flairquery/corpus/openlibrary/
COPY scripts/traffic.sh /opt/flairquery/scripts/
# Optional Open Library subset (profile M), copied by `make build` when it is cached locally
COPY ol-cache/ /opt/flairquery/ol-cache/
ENV FQ_OL_CACHE=/opt/flairquery/ol-cache
USER postgres
ENTRYPOINT []
