#!/bin/bash
#
# Verify the top-N candidates from a Tuner run with a proper deep-search match
# against the anchor weights. Prints a ranked table at the end so you can
# adopt whichever candidate actually plays best at the verification depth.
#
# Usage:
#   ./verify-finalists.sh [-n N] [-d DEPTH] [-g GAMES] [-s STATE_DIR]
#                         [-a ANCHOR] [-o OUTPUT]
#
# Defaults:
#   -n 5                              top 5 candidates
#   -d 6                              verify at depth 6
#   -g 200                            200 games per match
#   -s tuner-state                    where Tuner wrote history.csv
#   -a 5,10,16,26,42,70,120,1000      anchor (the engine's default)
#   -o tuner-state/finalists.txt      append-style log file
#
# Example:
#   ./verify-finalists.sh -n 8 -d 7 -g 300
#
# Each match takes ~10-30 minutes. With -n 5 -g 200, plan on 2-3 hours.

set -euo pipefail

# ---------- defaults ----------
TOP_N=5
DEPTH=6
GAMES=200
STATE_DIR=tuner-state
ANCHOR="5,10,16,26,42,70,120,1000"
OUTPUT=""

# ---------- arg parsing ----------
while getopts "n:d:g:s:a:o:h" opt; do
    case "$opt" in
        n) TOP_N="$OPTARG" ;;
        d) DEPTH="$OPTARG" ;;
        g) GAMES="$OPTARG" ;;
        s) STATE_DIR="$OPTARG" ;;
        a) ANCHOR="$OPTARG" ;;
        o) OUTPUT="$OPTARG" ;;
        h|*)
            sed -n '2,/^$/p' "$0"
            exit 0
            ;;
    esac
done

CSV="$STATE_DIR/history.csv"
[ -z "$OUTPUT" ] && OUTPUT="$STATE_DIR/finalists.txt"

if [ ! -f "$CSV" ]; then
    echo "error: $CSV not found" >&2
    exit 1
fi
if [ ! -d build ] || [ ! -f build/Main.class ]; then
    echo "error: build/Main.class not found. Run 'make' first." >&2
    exit 1
fi

# ---------- pick top-N rows by gauntlet win rate ----------
#
# CSV columns (from Tuner.java):
#   1: iter
#   2: timestamp
#   3..10: w0..w7
#   11: dscale
#   12: spsa+ wins
#   13: spsa- wins
#   14: gauntlet played
#   15: gauntlet wins
#   16: gauntlet winrate (blank if no gauntlet that iter)
#
# We keep only rows where gauntlet was actually run ($16 non-empty),
# dedupe by the (weights, dscale) tuple, sort by gauntlet winrate, take top N.

CANDIDATES=$(
    awk -F, 'NR > 1 && $16 != "" {
        weights = $3","$4","$5","$6","$7","$8","$9","$10
        dscale  = $11
        key     = weights"|"dscale
        rate    = $16 + 0
        if (!(key in seen) || rate > seen[key]) {
            seen[key]    = rate
            iter[key]    = $1
            wgt[key]     = weights
            ds[key]      = dscale
        }
    }
    END {
        for (k in seen) printf "%.4f\t%s\t%s\t%s\n", seen[k], iter[k], wgt[k], ds[k]
    }' "$CSV" | sort -k1,1 -gr | awk -v n="$TOP_N" 'NR<=n'
)

if [ -z "$CANDIDATES" ]; then
    echo "error: no gauntlet rows found in $CSV" >&2
    exit 1
fi

# ---------- header ----------
TS=$(date "+%Y-%m-%d %H:%M:%S")
{
    echo
    echo "================================================================"
    echo "Finalist verification run started $TS"
    echo "  CSV:      $CSV"
    echo "  Top N:    $TOP_N"
    echo "  Depth:    $DEPTH"
    echo "  Games:    $GAMES"
    echo "  Anchor:   $ANCHOR"
    echo "================================================================"
    echo
    echo "Candidates (by tuner gauntlet score):"
    echo "$CANDIDATES" | awk -F'\t' '{ printf "  rank %d  iter %4d  gauntlet %5.1f%%  ds=%s  %s\n", NR, $2, 100*$1, $4, $3 }'
    echo
} | tee -a "$OUTPUT"

# ---------- run matches ----------
declare -a RESULT_LINES=()

RANK=0
while IFS=$'\t' read -r RATE ITER WEIGHTS DSCALE; do
    RANK=$((RANK + 1))
    echo
    echo "----------------------------------------------------------------" | tee -a "$OUTPUT"
    echo "Match $RANK / $TOP_N : iter $ITER weights $WEIGHTS  dscale=$DSCALE" | tee -a "$OUTPUT"
    echo "  (Tuner gauntlet was ${RATE} over 50 games at depth 4)" | tee -a "$OUTPUT"
    echo "----------------------------------------------------------------" | tee -a "$OUTPUT"

    T0=$(date +%s)
    OUT=$(java -cp build Main match \
                --weights-a "$WEIGHTS" \
                --weights-b "$ANCHOR" \
                --defender-scale-a "$DSCALE" \
                --defender-scale-b 0 \
                --games "$GAMES" --depth "$DEPTH" --quiet)
    T1=$(date +%s)
    SECS=$((T1 - T0))

    A_WINS=$(echo "$OUT" | awk '/^A: /  { print $2 }')
    PCT=$(echo    "$OUT" | awk '/^A win rate:/ { print $4 }')

    echo "$OUT" | tee -a "$OUTPUT"
    echo "  (match took ${SECS}s)" | tee -a "$OUTPUT"

    RESULT_LINES+=("$RANK|$ITER|$A_WINS|$GAMES|$PCT|$WEIGHTS|$DSCALE")
done <<< "$CANDIDATES"

# ---------- ranked summary ----------
{
    echo
    echo "================================================================"
    echo "FINAL RANKING (by verification win rate vs anchor at depth $DEPTH)"
    echo "================================================================"
    printf "%-6s %-6s %-12s %-9s %-8s %s\n" "rank" "iter" "wins/games" "win%" "dscale" "weights"
    for row in "${RESULT_LINES[@]}"; do echo "$row"; done | \
        awk -F'|' '{
            pct = $5; sub(/%$/, "", pct)
            printf "%s|%s|%s|%s|%.2f|%s|%s|%s\n", $1, $2, $3, $4, pct, $5, $6, $7
        }' | sort -t'|' -k5,5 -gr | \
        awk -F'|' '{
            printf "%-6s %-6s %-12s %-9s %-8s %s\n",
                   NR, $2, $3"/"$4, $6, $8, $7
        }'
    echo
    echo "Recommended: rank 1 above (assuming its win rate is meaningfully > 50%)."
    echo "Verification took $(( $(date +%s) - $(date -d "$TS" +%s) ))s total."
    echo "================================================================"
} | tee -a "$OUTPUT"
