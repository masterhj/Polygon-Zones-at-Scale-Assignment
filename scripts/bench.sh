#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"
JDK25_HOME="${BENCH_JDK25_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}"
if [[ ! -x "$JDK25_HOME/bin/java" ]]; then
  echo "JDK 25 was not found at JAVA_HOME=$JDK25_HOME" >&2
  exit 2
fi
export JAVA_HOME="$JDK25_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
BENCH_JAR="$REPO_ROOT/zone-bench/target/benchmarks.jar"
OUT="$REPO_ROOT/docs/benchmarks"
mkdir -p "$OUT"

{
  date -u '+date_utc=%Y-%m-%dT%H:%M:%SZ'
  uname -a
  if command -v lscpu >/dev/null 2>&1; then lscpu; fi
  if command -v system_profiler >/dev/null 2>&1; then system_profiler SPHardwareDataType | awk '/Model Name:|Chip:|Total Number of Cores:|Memory:/ {print}'; fi
  if command -v free >/dev/null 2>&1; then free -h; fi
  if command -v sysctl >/dev/null 2>&1; then sysctl -n hw.memsize 2>&1 || true; fi
  java -version 2>&1
  printf 'java_home=%s\n' "$JAVA_HOME"
  printf 'java_tool_options=%s\n' "${JAVA_TOOL_OPTIONS:-<unset>}"
  printf 'jvm_args=%s\n' "${BENCH_JVM_ARGS:--Xms512m -Xmx6g}"
  printf 'docker='; if command -v docker >/dev/null 2>&1; then docker --version; else echo unavailable; fi
} > "$OUT/environment.txt"

JAVA_ARGS="${BENCH_JVM_ARGS:--Xms512m -Xmx6g}"
./mvnw -q -Dmaven.repo.local="${MAVEN_REPO_LOCAL:-/private/tmp/moveinsync-maven-repository}" -pl zone-bench -am package

# The benchmark annotations specify 5 x 2 s warmup, 10 x 2 s measurement, and two forks.
# JMH's GC profiler is enabled for all parameter combinations, including the no-allocation probe.
java $JAVA_ARGS -jar "$BENCH_JAR" 'com.polyzones.bench.PointQueryBenchmark.*' -prof gc -rf json -rff "$OUT/jmh-point-query.json"
java $JAVA_ARGS -jar "$BENCH_JAR" 'com.polyzones.bench.IndexBuildBenchmark.*' -rf json -rff "$OUT/jmh-index-build.json"
java $JAVA_ARGS -cp "$BENCH_JAR" com.polyzones.bench.CandidateStatsReport "$OUT/candidate-counts.csv"
java $JAVA_ARGS -cp "$BENCH_JAR" com.polyzones.bench.IndexMemoryReport "$OUT/memory.csv" | tee "$OUT/memory-run.txt"
java $JAVA_ARGS -cp "$BENCH_JAR" com.polyzones.bench.PointQueryLoadTest baseline | tee "$OUT/load-baseline.txt"
java $JAVA_ARGS -cp "$BENCH_JAR" com.polyzones.bench.PointQueryLoadTest rebuild | tee "$OUT/load-rebuild.txt"
python3 - "$OUT/load-baseline.txt" "$OUT/load-rebuild.txt" "$OUT/load-summary.txt" <<'PY'
import re, sys
from pathlib import Path
def read(path):
    text=Path(path).read_text()
    qps=float(re.search(r"achieved_completed_qps=([0-9.]+)",text).group(1))
    p99=int(re.search(r"service_p99_ns=(\d+)",text).group(1))
    schedule_p99=int(re.search(r"schedule_to_done_p99_ns=(\d+)",text).group(1))
    drops=int(re.search(r"dropped_overload=(\d+)",text).group(1))
    return qps,p99,schedule_p99,drops
bq,bp,bs,bd=read(sys.argv[1]); rq,rp,rs,rd=read(sys.argv[2])
Path(sys.argv[3]).write_text(f"baseline_qps={bq:.2f}\nbaseline_service_p99_ns={bp}\nbaseline_schedule_to_done_p99_ns={bs}\nbaseline_dropped={bd}\nrebuild_qps={rq:.2f}\nrebuild_service_p99_ns={rp}\nrebuild_schedule_to_done_p99_ns={rs}\nrebuild_dropped={rd}\nservice_p99_difference_ns={rp-bp}\nservice_p99_difference_percent={(rp-bp)*100/bp if bp else 0:.3f}\nschedule_to_done_p99_difference_ns={rs-bs}\nschedule_to_done_p99_difference_percent={(rs-bs)*100/bs if bs else 0:.3f}\n")
PY
