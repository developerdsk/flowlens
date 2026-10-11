#!/usr/bin/env bash
# FlowLens launcher (macOS / Linux) - browser mode.
# Builds the engine if needed (Maven is downloaded by the wrapper), then starts the local viewer server.
# For the installable desktop app, see desktop/README.md.
set -e
cd "$(dirname "$0")"

PORT="${1:-7070}"
JAR="analyzer/target/flowlens-analyzer.jar"

if [ ! -f "$JAR" ]; then
  echo "Building FlowLens (first run)..."
  (cd analyzer && ./mvnw -q -B -DskipTests package)
fi

exec java -jar "$JAR" --serve "$PORT" --open
