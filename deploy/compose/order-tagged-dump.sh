#!/usr/bin/env bash
# Reads a plain-format pg_dump on stdin and writes it to stdout unchanged, except that the rows of every COPY block
# whose column list holds `install_tag` (the epoch-tagged content tables: token, cluster and slot sightings, per-install
# health; #86, #80) are sorted by that tag.
#
# Why (census#80, Sol S2): pg_dump writes a table in heap order, which is roughly insertion order, so the rows one
# upload wrote sit together in a dump and a stolen backup could group one install's rows without any key. An epoch tag
# is an HMAC, so ordering by it is effectively random and independent of upload batches; rows sharing a tag already
# share it on purpose (one install's rows for one object within one epoch). The output restores with psql exactly as
# before: only row order within those COPY blocks changes.
set -euo pipefail
work="$(mktemp -d "${TMPDIR:-/tmp}/census-order.XXXXXX")"
trap 'rm -rf -- "$work"' EXIT
export LC_ALL=C
awk -v block="$work/block" '
    copying {
        if ($0 == "\\.") {
            close(block)
            fflush()
            if (system("sort -t \"\t\" -s -k" column "," column " -- \"" block "\"") != 0) exit 2
            print
            copying = 0
        } else {
            print > block
        }
        next
    }
    /^COPY [^ ]+ \(.*\) FROM stdin;$/ {
        columns = $0
        sub(/^COPY [^ ]+ \(/, "", columns)
        sub(/\) FROM stdin;$/, "", columns)
        count = split(columns, names, ", ")
        column = 0
        for (i = 1; i <= count; i++) if (names[i] == "install_tag") column = i
        print
        if (column > 0) {
            copying = 1
            printf "" > block
            close(block)
        }
        next
    }
    { print }
    END { if (copying) exit 3 }
'
