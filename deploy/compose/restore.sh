#!/usr/bin/env bash
set -euo pipefail
umask 077
if [[ $# -ne 3 || ! -r "$1" || ! -r "$2" || ! -r "$3" ]]; then
    printf 'Usage: %s /path/to/census.sql.gz /path/to/latest-withdrawals.csv /path/to/latest-filter-floor.csv\n' "$0" >&2
    exit 1
fi
dump="$(realpath -- "$1")"
journal="$(realpath -- "$2")"
floor="$(realpath -- "$3")"
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
gzip -t -- "$dump"
staged="$(mktemp -d -t census-recovery.XXXXXX)"
trap 'rm -rf -- "$staged"' EXIT
cat -- "$journal" > "$staged/withdrawals.csv"
cat -- "$floor" > "$staged/filter-floor.csv"
chmod 0600 -- "$staged/withdrawals.csv" "$staged/filter-floor.csv"
docker compose run --rm --no-deps --user "$(id -u):$(id -g)" -v "$staged":/recovery:ro --entrypoint /bin/sh census     -c 'test -r /recovery/withdrawals.csv && test -r /recovery/filter-floor.csv'     || { printf 'Refusing restore: recovery journals are not readable.\n' >&2; exit 1; }
printf 'Restore %s into a FRESH census database? Type RESTORE: ' "$dump"
read -r confirmation
[[ "$confirmation" == RESTORE ]] || { printf 'Cancelled.\n'; exit 1; }
# Keep CURRENT deployment .env and the lifecycle-capable image. Never restore their historical copies.
docker compose stop census
objects="$(docker compose exec -T postgres psql -X -U census -d census -At -v ON_ERROR_STOP=1     -c "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg_toast%' AND c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f');")"
if [[ "$objects" != 0 ]]; then
    printf 'Refusing restore: census must be a fresh database. Census remains stopped.\n' >&2
    exit 1
fi
gzip -dc -- "$dump" | docker compose exec -T postgres psql -X -U census -d census --single-transaction -v ON_ERROR_STOP=1
# Migrate, merge by maximum, replay withdrawals, remove unsafe revisions, and drain all lifecycle work.
# Any failure exits here, leaving census stopped.
docker compose run --rm --no-deps --user "$(id -u):$(id -g)" -v "$staged":/recovery:ro census     --reapply-withdrawals /recovery/withdrawals.csv --filter-floor /recovery/filter-floor.csv
printf 'Restore complete; withdrawal and filter-floor replay and lifecycle catch-up succeeded. Review, then run docker compose up -d census.\n'
