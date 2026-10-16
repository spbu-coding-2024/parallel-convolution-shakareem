#!/usr/bin/env bash
# Run all three benchmarks (tasks 1-3) with chart generation, on a quiet machine:
# 1. stop gradle/kotlin daemons (they restart automatically; while alive they steal CPU)
# 2. serial -> parallel -> pipeline
# 3. stop daemons again
# Charts are saved headless (no X11/Wayland needed) since commit "fix: force headless AWT".
set -e
cd "$(dirname "$0")"

./gradlew --stop >/dev/null 2>&1 || true
pkill -f KotlinCompileDaemon 2>/dev/null || true
sleep 1

for t in serial parallel pipeline; do
  echo ">>> ./gradlew benchmark -Ptask=$t"
  ./gradlew benchmark "-Ptask=$t"
done

./gradlew --stop >/dev/null 2>&1 || true
echo
echo "Done. Tables: docs/benchmark_task{1,2,3}.csv   Charts: docs/charts/*.png"
