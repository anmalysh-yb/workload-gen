#!/usr/bin/env bash
# Launcher for the workload generator. Builds the boot jar if it is missing or out of date,
# then forwards every argument to the CLI.
#
#   ./workload-gen.sh --help
#   ./workload-gen.sh -H node1,node2,node3 -W secret -w cpu-heavy -t 20m
#   ./workload-gen.sh -H node1 -W secret --chain cpu-heavy:20m,connection-churn:30m
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jar="$root/build/libs/workload-gen.jar"

# JAVA_OPTS is passed to the JVM, e.g. JAVA_OPTS=-Xmx2g for a wide thread count.
java_opts=${JAVA_OPTS:-}

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  # Quiet build: only the workload's own output should reach the console.
  "$root/gradlew" -p "$root" -q bootJar >&2
fi

if [[ ! -f "$jar" ]]; then
  echo "workload-gen.jar not found at $jar. Run '$root/gradlew bootJar' first." >&2
  exit 1
fi

# shellcheck disable=SC2086 # java_opts is intentionally word-split.
exec java $java_opts -jar "$jar" "$@"
