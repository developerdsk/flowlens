#!/usr/bin/env bash
# FlowLens launcher (macOS / Linux).
# Builds the analyzer if needed, then starts the local viewer server.
set -e
cd "$(dirname "$0")"

PORT="${1:-7070}"
JAR="analyzer/target/flowlens-analyzer.jar"

if [ ! -f "$JAR" ]; then
  echo "Building FlowLens (first run)…"
  (cd analyzer && mvn -q -B package)
fi

echo "Starting FlowLens on http://localhost:$PORT"
echo "Open that URL, then click 'Open project' or paste a project path."
java -jar "$JAR" --serve "$PORT"
