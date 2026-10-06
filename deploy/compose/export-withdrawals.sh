#!/usr/bin/env bash
set -euo pipefail
umask 077
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
mkdir -p backups/withdrawals
journal="backups/withdrawals/withdrawals-$(date -u +%Y%m%dT%H%M%SZ).csv"
temporary="$(mktemp "${journal}.XXXXXX")"
trap 'rm -f -- "$temporary"' EXIT
docker compose exec -T postgres psql -X -U census -d census -At -v ON_ERROR_STOP=1 \
    -c "COPY (SELECT install_id_hash, withdrawn_at FROM withdrawals ORDER BY withdrawn_at) TO STDOUT WITH (FORMAT csv, HEADER true)" > "$temporary"
mv -- "$temporary" "$journal"
if [[ -n "${BACKUP_BUCKET:-}" ]]; then
    aws s3 cp "$journal" "s3://${BACKUP_BUCKET#s3://}/withdrawals/$(basename -- "$journal")"
fi
# Retain today's journals plus the previous 13 UTC dates, like backup.sh.
cutoff="$(date -u -d '13 days ago' +%Y%m%d)"
for candidate in backups/withdrawals/withdrawals-????????T??????Z.csv; do
    [[ -f "$candidate" ]] || continue
    day="${candidate#backups/withdrawals/withdrawals-}"
    day="${day%%T*}"
    if [[ "$day" < "$cutoff" ]]; then rm -- "$candidate"; fi
done
rows="$(( $(wc -l < "$journal") - 1 ))"
printf 'Withdrawal journal exported: %s (%s rows)\n' "$journal" "$rows"
