#!/usr/bin/env bash
# Starts Ollama (if not already running) and then the Spring Boot app.
# Usage: ./scripts/run.sh
set -euo pipefail
cd "$(dirname "$0")/.."

OLLAMA_BIN="${OLLAMA_BIN:-ollama}"
if ! command -v "$OLLAMA_BIN" >/dev/null 2>&1; then
  # fall back to the user-local install used during development (see README "no-root install")
  if [ -x "$HOME/.local/ollama/bin/ollama" ]; then
    OLLAMA_BIN="$HOME/.local/ollama/bin/ollama"
  else
    echo "ERROR: 'ollama' not found on PATH and no local install at ~/.local/ollama/bin/ollama."
    echo "Install it first — see README.md 'Setup' section."
    exit 1
  fi
fi

if ! curl -s -m 2 http://localhost:11434/api/tags >/dev/null 2>&1 && \
   ! python3 -c "import urllib.request;urllib.request.urlopen('http://localhost:11434/api/tags',timeout=2)" >/dev/null 2>&1; then
  echo "Starting Ollama server ($OLLAMA_BIN serve)..."
  nohup "$OLLAMA_BIN" serve > /tmp/ollama-serve.log 2>&1 &
  disown
  sleep 3
else
  echo "Ollama server already running."
fi

echo "Starting Local AI Assistant (Spring Boot)..."
./mvnw spring-boot:run
