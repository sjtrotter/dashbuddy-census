#!/usr/bin/env bash
set -euo pipefail
umask 077
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
mkdir -p backups
dump="backups/census-$(date -u +%F).sql.gz"
temporary="$(mktemp "${dump}.XXXXXX")"
trap 'rm -f -- "$temporary"' EXIT
# #80 (Sol S2): epoch-tagged rows are written in tag order, not heap order, so a dump does not keep one upload's rows
# together. Sort spills go to the data volume, beside the dump, never to the host's /tmp.
# #81: no row of the "Ask the developer" quarantine is ever backed up, and the day's slot release (rebuilt from
# slot_sightings every day) is not either; their tables are dumped empty. TaggedDumpOrderTest runs these flags.
docker compose exec -T postgres pg_dump -U census --exclude-table-data=quarantine_items \
    --exclude-table-data=slot_release --exclude-table-data=slot_release_days census \
    | TMPDIR="$PWD/backups" ./order-tagged-dump.sh | gzip > "$temporary"
mv -- "$temporary" "$dump"
if [[ -n "${BACKUP_BUCKET:-}" ]]; then
    aws s3 cp "$dump" "s3://${BACKUP_BUCKET#s3://}/$(basename -- "$dump")"
fi
# Retain today's backup plus the previous 13 UTC dates. Configure S3 lifecycle too.
cutoff="$(date -u -d '13 days ago' +%F)"
for candidate in backups/census-????-??-??.sql.gz; do
    [[ -f "$candidate" ]] || continue
    day="${candidate#backups/census-}"
    day="${day%.sql.gz}"
    if [[ "$day" < "$cutoff" ]]; then rm -- "$candidate"; fi
done
printf 'Backup created: %s\n' "$dump"
# Both recovery exports happen AFTER the dump. Either failure fails the backup operation;
# an intact dump alone is not a complete recovery set. Preserve it for diagnosis/retry.
./export-withdrawals.sh
./export-filter-floor.sh
printf 'Backup recovery journals exported successfully.\n'
