#!/usr/bin/env bash
set -euo pipefail
umask 077
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
./export-withdrawals.sh
mkdir -p backups
dump="backups/census-$(date -u +%F).sql.gz"
temporary="$(mktemp "${dump}.XXXXXX")"
trap 'rm -f -- "$temporary"' EXIT
docker compose exec -T postgres pg_dump -U census census | gzip > "$temporary"
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
