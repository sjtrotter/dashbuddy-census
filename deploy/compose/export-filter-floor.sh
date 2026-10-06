#!/usr/bin/env bash
set -euo pipefail
umask 077
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
mkdir -p backups/filter-floor
journal="backups/filter-floor/filter-floor-$(date -u +%Y%m%dT%H%M%SZ).csv"
temporary="$(mktemp "${journal}.XXXXXX")"
trap 'rm -f -- "$temporary"' EXIT
docker compose exec -T postgres psql -X -U census -d census -At -v ON_ERROR_STOP=1 \
    -c "COPY (SELECT min_filter_rev, changed_day FROM filter_floor WHERE singleton) TO STDOUT WITH (FORMAT csv, HEADER true)" > "$temporary"
# Validate the complete policy-only journal, fsync, and atomically publish. No silent empty exports.
python3 - "$temporary" "$journal" <<'PY'
import datetime, os, re, sys
source, target = sys.argv[1:]
with open(source, 'r+', encoding='ascii') as stream:
    value = stream.read(129)
    match = re.fullmatch(r'min_filter_rev,changed_day\n([1-9][0-9]*),(\d{4}-\d{2}-\d{2})\n', value)
    if not match or int(match[1]) > 2147483647 or datetime.date.fromisoformat(match[2]) > datetime.datetime.now(datetime.timezone.utc).date():
        raise SystemExit('Invalid filter floor journal')
    os.fsync(stream.fileno())
os.replace(source, target)
fd = os.open(os.path.dirname(target), os.O_RDONLY)
try:
    os.fsync(fd)
finally:
    os.close(fd)
PY
if [[ -n "${BACKUP_BUCKET:-}" ]]; then
    aws s3 cp "$journal" "s3://${BACKUP_BUCKET#s3://}/filter-floor/$(basename -- "$journal")"
fi
# Never age these policy-only journals out; all older restorable dumps must respect the maximum.
printf 'Filter floor journal exported: %s\n' "$journal"
