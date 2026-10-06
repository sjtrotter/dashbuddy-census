#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 2 || ! -f "$1" || ( "$2" != --no-journal && ( ! -f "$2" || ! -r "$2" ) ) ]]; then
    printf 'Usage: %s /path/to/census-YYYY-MM-DD.sql.gz (/path/to/withdrawals-*.csv | --no-journal)\n' "$0" >&2
    exit 1
fi
dump="$(cd -- "$(dirname -- "$1")" && pwd)/$(basename -- "$1")"
journal="$2"
if [[ "$journal" != --no-journal ]]; then
    journal="$(cd -- "$(dirname -- "$journal")" && pwd)/$(basename -- "$journal")"
fi
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
gzip -t -- "$dump"
if [[ "$journal" != --no-journal ]]; then
    # The replay container runs as the invoking identity with every capability dropped: it cannot read another
    # uid's 0600 file even as root. Stage a private copy owned by the invoker and PROVE the container can read
    # it before any SQL is restored — a failure after the restore would leave a populated database that
    # refuses a second run.
    staged="$(mktemp -t census-withdrawals.XXXXXX.csv)"
    trap 'rm -f -- "$staged"' EXIT
    cat -- "$journal" > "$staged"
    chmod 0600 -- "$staged"
    docker compose run --rm --no-deps --user "$(id -u):$(id -g)" -v "$staged":/journal.csv:ro --entrypoint /bin/sh census -c 'test -r /journal.csv' \
        || { printf 'Refusing restore: the replay container cannot read the staged journal.\n' >&2; exit 1; }
    journal="$staged"
fi
printf 'Restore %s into a FRESH census database? Type RESTORE: ' "$dump"
read -r confirmation
[[ "$confirmation" == RESTORE ]] || { printf 'Cancelled.\n'; exit 1; }
# Prevent startup migrations/writes racing the emptiness check and restore.
docker compose stop census
objects="$(docker compose exec -T postgres psql -X -U census -d census -At -v ON_ERROR_STOP=1 \
    -c "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg_toast%' AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f');")"
if [[ "$objects" != 0 ]]; then
    printf 'Refusing restore: census must be a fresh database with no user relations. Census remains stopped.\n' >&2
    exit 1
fi
gzip -dc -- "$dump" | docker compose exec -T postgres psql -X -U census -d census \
    --single-transaction -v ON_ERROR_STOP=1
if [[ "$journal" == --no-journal ]]; then
    printf 'WARNING: replay uses tombstones INSIDE the dump only. Withdrawals after the dump are NOT covered.\n' >&2
    docker compose run --rm --no-deps census --reapply-withdrawals /dev/null
else
    # Journals are mode 0600; use the caller's identity to read the bind mount without making it public.
    docker compose run --rm --no-deps --user "$(id -u):$(id -g)" -v "$journal":/journal.csv:ro census --reapply-withdrawals /journal.csv
fi
printf 'Restore complete. Withdrawals replayed. Review the data, then run docker compose up -d census.\n'
