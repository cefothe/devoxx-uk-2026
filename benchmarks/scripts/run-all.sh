#!/usr/bin/env bash
# Run the full Devoxx UK benchmark suite and capture JSON for charting.
# Total runtime ~30 minutes on the AMD EPYC reference box.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESULTS_DIR="$ROOT/../results/raw"
JAR="$ROOT/target/benchmarks.jar"

mkdir -p "$RESULTS_DIR"

if [[ ! -f "$JAR" ]]; then
    echo "Building benchmarks.jar (one-time)..."
    (cd "$ROOT" && ./mvnw -q clean package)
fi

run_category() {
    local category="$1"
    local pattern="$2"
    echo
    echo "=========================================="
    echo "  Running: $category"
    echo "  Pattern: $pattern"
    echo "=========================================="
    java -jar "$JAR" \
        -rf json \
        -rff "$RESULTS_DIR/$category.json" \
        -o "$RESULTS_DIR/$category.txt" \
        "$pattern"
}

run_category "memory"      "com.devoxx.lowlatency.memory.*"
run_category "concurrency" "com.devoxx.lowlatency.concurrency.*"
run_category "libraries"   "com.devoxx.lowlatency.libraries.*"
run_category "jit"         "com.devoxx.lowlatency.jit.*"

echo
echo "Done. JSON results in: $RESULTS_DIR"
echo "Use these to feed the Marp slide charts."
