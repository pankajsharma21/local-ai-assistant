#!/usr/bin/env bash
# Pulls the local LLM this project talks to by default. Run once after installing Ollama.
# The embedding model needs NO download here — it's bundled inside the langchain4j-embeddings-all-minilm-l6-v2
# jar and runs in-process (see ModelConfig.java).
set -euo pipefail

OLLAMA_BIN="${OLLAMA_BIN:-ollama}"
if ! command -v "$OLLAMA_BIN" >/dev/null 2>&1 && [ -x "$HOME/.local/ollama/bin/ollama" ]; then
  OLLAMA_BIN="$HOME/.local/ollama/bin/ollama"
fi

MODEL="${1:-llama3.2}"
echo "Pulling '$MODEL' via $OLLAMA_BIN (this can take a few minutes depending on model size)..."
"$OLLAMA_BIN" pull "$MODEL"
echo "Done. Set assistant.ollama.chat-model=$MODEL in application.yml if you didn't use the default."
