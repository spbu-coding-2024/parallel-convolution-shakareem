#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# bench-all.sh — full benchmark suite on a controlled, quiet machine.
#
# Protocol (every control is optional except the measurement loop):
#   1. precompile outside the measured region, then stop gradle/kotlin daemons
#   2. (root) pin all cores to one common frequency + performance governor:
#      removes boost/thermal drift and puts P/E cores on equal footing, so
#      thread scaling measures IPC + memory bandwidth, not a clock lottery
#   3. (root) IRQ steering: stop irqbalance and move ALL device interrupts to
#      the housekeeping cores (the complement of --cpus). With --cpus=all there
#      are no housekeeping cores — use e.g. --cpus 0-11 to reserve 12,13.
#   4. ROUNDS independent invocations of every task, pinned to --cpus, each
#      writing raw ns samples to its own docs/bench/<stamp>/runK/
#      -> the cross-round spread IS the reproducibility measurement
#   5. restore frequencies/governors/IRQ affinity (trap on exit/interrupt)
#   6. pool all rounds and draw every chart + stats/repeatability CSVs
#
# Usage:
#   sudo -E ./bench-all.sh                     # 3 rounds, all cpus, freq=auto
#   sudo -E ./bench-all.sh --rounds 5
#   sudo -E ./bench-all.sh --cpus 0-11         # 12 bench cores + 2 housekeeping
#   sudo -E ./bench-all.sh --cpus p            # P-cores only (heterogeneity probe)
#   sudo -E ./bench-all.sh --freq none         # keep boost, governor only
#   ./bench-all.sh --no-pin --no-irq           # rootless mode
#
# Options:
#   --rounds N     independent invocations per task          (default 3)
#   --cpus SPEC    "all", "p" (max-frequency cores) or "0-11" / "0,1,2" (default all)
#   --freq KHZ     pin frequency; "auto" = slowest core's max (default);
#                  "none" = governor only
#   --no-pin       skip governor/frequency control
#   --no-irq       skip IRQ steering / irqbalance stop
#   --tasks LIST   subset: "serial parallel pipeline"
#   --reps N       override measured rounds per config (defaults: 7 / 9 / 5)
# ---------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")"

ROUNDS=3; CPUS=all; FREQ=auto; PIN=1; IRQ=1; TASKS="serial parallel pipeline"; REPS=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --rounds) ROUNDS=$2; shift 2 ;;
    --cpus)   CPUS=$2; shift 2 ;;
    --freq)   FREQ=$2; shift 2 ;;
    --reps)   REPS=$2; shift 2 ;;
    --no-pin) PIN=0; shift ;;
    --no-irq) IRQ=0; shift ;;
    --tasks)  TASKS=$2; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

MAXCPU=$(( $(grep -c '^processor' /proc/cpuinfo) - 1 ))

cpu_list() {
  case "$1" in
    all) seq -s, 0 "$MAXCPU" ;;
    p)
      local top out=()
      top=$(cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq | sort -rn | head -1)
      for c in $(seq 0 "$MAXCPU"); do
        [[ "$(cat "/sys/devices/system/cpu/cpu$c/cpufreq/cpuinfo_max_freq")" == "$top" ]] && out+=("$c")
      done
      local IFS=,; echo "${out[*]}" ;;
    *) echo "$1" ;;
  esac
}

BENCH_CPUS=$(cpu_list "$CPUS")
IFS=, read -r -a BENCH_ARR <<< "$BENCH_CPUS"
HK=()
for c in $(seq 0 "$MAXCPU"); do
  hit=0
  for b in "${BENCH_ARR[@]}"; do [[ $b == "$c" ]] && hit=1; done
  (( hit )) || HK+=("$c")
done

ROOT=0
if [[ $EUID == 0 ]] || sudo -n true 2>/dev/null; then ROOT=1; fi

echo "=== bench-all.sh ==="
echo "bench cpus   : $BENCH_CPUS      housekeeping: ${HK[*]:-<none>}"
echo "rounds       : $ROUNDS          tasks: $TASKS"
echo "freq         : $FREQ            (auto = slowest core's max kHz)"
echo "root         : $ROOT"
echo

if [[ $ROOT == 0 ]]; then
  if [[ $PIN == 1 || $IRQ == 1 ]]; then
    echo "WARNING: no root — skipping frequency pinning and IRQ steering."
    echo "         taskset + interleaving + multi-round pooling still active."
    echo "         re-run with sudo -E for the full quiet-machine protocol."
  fi
  PIN=0; IRQ=0
fi

declare -A OLD_GOV OLD_MIN OLD_MAX
OLD_IRQ_FILE=""; IRQBALANCE_WAS_ACTIVE=0

restore() {
  echo; echo "== restoring system state =="
  if [[ $PIN == 1 ]]; then
    for c in $(seq 0 "$MAXCPU"); do
      d="/sys/devices/system/cpu/cpu$c/cpufreq"; [[ -d $d ]] || continue
      echo "${OLD_GOV[$c]}" > "$d/scaling_governor" 2>/dev/null || true
      [[ -n "${OLD_MIN[$c]:-}" ]] && echo "${OLD_MIN[$c]}" > "$d/scaling_min_freq" 2>/dev/null || true
      [[ -n "${OLD_MAX[$c]:-}" ]] && echo "${OLD_MAX[$c]}" > "$d/scaling_max_freq" 2>/dev/null || true
    done
  fi
  if [[ $IRQ == 1 && -n "$OLD_IRQ_FILE" ]]; then
    while IFS=$'\t' read -r irq mask; do
      echo "$mask" > "/proc/irq/$irq/smp_affinity" 2>/dev/null || true
    done < "$OLD_IRQ_FILE"
    rm -f "$OLD_IRQ_FILE"
    if [[ $IRQBALANCE_WAS_ACTIVE == 1 ]]; then systemctl start irqbalance 2>/dev/null || true; fi
  fi
  ./gradlew --stop >/dev/null 2>&1 || true
}
trap restore EXIT INT TERM

# ---- frequency / governor pinning -----------------------------------------
if [[ $PIN == 1 ]]; then
  if [[ "$FREQ" == auto ]]; then
    # every core sustains the slowest core's max: also removes P/E boost asymmetry
    FREQ=$(cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq | sort -n | head -1)
    [[ -z "$FREQ" ]] && { echo "no freq info — pin disabled"; PIN=0; }
  fi
  if [[ $PIN == 1 && "$FREQ" != none ]]; then
    echo "== pinning all $((MAXCPU + 1)) cpus: governor=performance, freq=${FREQ} kHz =="
    for c in $(seq 0 "$MAXCPU"); do
      d="/sys/devices/system/cpu/cpu$c/cpufreq"
      OLD_GOV[$c]=$(cat "$d/scaling_governor"); OLD_MIN[$c]=$(cat "$d/scaling_min_freq"); OLD_MAX[$c]=$(cat "$d/scaling_max_freq")
      echo "$FREQ" > "$d/scaling_min_freq"
      echo "$FREQ" > "$d/scaling_max_freq"
      echo performance > "$d/scaling_governor"
    done
  elif [[ $PIN == 1 ]]; then
    echo "== governor=performance on all cpus (boost kept) =="
    for c in $(seq 0 "$MAXCPU"); do
      d="/sys/devices/system/cpu/cpu$c/cpufreq"
      OLD_GOV[$c]=$(cat "$d/scaling_governor"); OLD_MIN[$c]=""; OLD_MAX[$c]=""
      echo performance > "$d/scaling_governor"
    done
  fi
fi

# ---- IRQ steering -----------------------------------------------------------
if [[ $IRQ == 1 ]]; then
  if [[ ${#HK[@]} == 0 ]]; then
    echo "WARNING: --cpus covers all cores, no housekeeping left — skipping IRQ steering"
    IRQ=0
  else
    m=0
    for c in "${HK[@]}"; do m=$(( m | (1 << c) )); done
    MASK=$(printf '%x' "$m")
    if systemctl is-active --quiet irqbalance; then IRQBALANCE_WAS_ACTIVE=1; fi
    systemctl stop irqbalance 2>/dev/null || true
    OLD_IRQ_FILE=$(mktemp)
    for d in /proc/irq/[0-9]*/; do
      irq=$(basename "$d")
      [[ -f "$d/smp_affinity" ]] || continue
      cur=$(tr -d ' ,\n' < "$d/smp_affinity")
      printf '%s\t%s\n' "$irq" "$cur" >> "$OLD_IRQ_FILE"
      echo "$MASK" > "$d/smp_affinity" 2>/dev/null || true
    done
    echo "== routed all IRQs to housekeeping cpus ${HK[*]} (mask=$MASK) =="
  fi
fi

# ---- precompile, then start ONE gradle daemon INSIDE the pinned cpuset -----
# JavaExec forks the measured JVM from the daemon, so pinning the daemon pins
# every benchmark invocation.
echo "== pre-compiling (not measured) =="
./gradlew :app:assemble :app:compileTestKotlin -q >/dev/null
./gradlew --stop >/dev/null 2>&1 || true
pkill -f KotlinCompileDaemon 2>/dev/null || true
sleep 1
taskset -c "$BENCH_CPUS" ./gradlew help -q >/dev/null

STAMP=$(date +%Y%m%d-%H%M%S)
BASE="docs/bench/$STAMP"

for r in $(seq 1 "$ROUNDS"); do
  OUT="$BASE/run$r"
  mkdir -p "$OUT"
  for t in $TASKS; do
    echo ">>> round $r/$ROUNDS  task=$t  ->  $OUT"
    if [[ -n "$REPS" ]]; then
      ./gradlew -q benchmark "-Ptask=$t" "-Pout=$OUT" "-Preps=$REPS"
    else
      ./gradlew -q benchmark "-Ptask=$t" "-Pout=$OUT"
    fi
  done
done

echo ">>> drawing charts + stats from all $ROUNDS rounds"
./gradlew -q charts "-Psrc=$BASE"

echo
echo "DONE."
echo "  raw ns samples  : $BASE/run{1..$ROUNDS}/benchmark_task*.csv (+ env.txt per round)"
echo "  per-config stats: docs/benchmark_stats_task*.csv (median, IQR, bootstrap 95% CI)"
echo "  reproducibility : docs/benchmark_repeatability_task*.csv + docs/charts/task*_repeatability.png"
echo "  charts          : docs/charts/"
