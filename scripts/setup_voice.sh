#!/usr/bin/env bash
# One-time setup for the voice feature: downloads prebuilt whisper.cpp (local speech-to-text) and
# Piper (local text-to-speech) binaries + a small voice model. No compiler/cmake needed - both
# projects publish ready-to-run Linux x64 releases. Everything ends up under ./tools, which is
# git-ignored on purpose (these are large binaries, not source).
#
# After this finishes, assistant.voice.enabled is already true by default in application.yml
# (the paths there match exactly what this script produces) - just restart the app.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p tools

# ---------------------------------------------------------------------------
# 1. whisper.cpp (speech-to-text) — prebuilt Ubuntu x64 release
# ---------------------------------------------------------------------------
if [ ! -x "tools/whisper/whisper-cli" ]; then
  echo "== Downloading whisper.cpp (prebuilt) =="
  WHISPER_TAG=$(python3 -c "
import urllib.request, json
req = urllib.request.Request('https://api.github.com/repos/ggml-org/whisper.cpp/releases/latest', headers={'User-Agent':'curl'})
print(json.loads(urllib.request.urlopen(req, timeout=15).read())['tag_name'])
")
  mkdir -p tools/whisper
  wget -q -O tools/whisper-bin.tar.gz \
    "https://github.com/ggml-org/whisper.cpp/releases/download/${WHISPER_TAG}/whisper-bin-ubuntu-x64.tar.gz"
  tar -xzf tools/whisper-bin.tar.gz -C tools
  # the archive extracts to tools/whisper-bin-ubuntu-x64/ - flatten it into tools/whisper/
  rsync -a tools/whisper-bin-ubuntu-x64/ tools/whisper/ 2>/dev/null || cp -r tools/whisper-bin-ubuntu-x64/. tools/whisper/
  rm -rf tools/whisper-bin-ubuntu-x64 tools/whisper-bin.tar.gz
  chmod +x tools/whisper/whisper-cli
  echo "whisper.cpp ready at tools/whisper/whisper-cli"
else
  echo "whisper.cpp already set up, skipping."
fi

if [ ! -f "tools/whisper/models/ggml-base.en.bin" ]; then
  echo "== Downloading whisper base.en model (~142MB) =="
  mkdir -p tools/whisper/models
  wget -q -O tools/whisper/models/ggml-base.en.bin \
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin"
  echo "Model ready."
else
  echo "whisper model already present, skipping."
fi

# ---------------------------------------------------------------------------
# 2. Piper (text-to-speech)
# ---------------------------------------------------------------------------
if [ ! -x "tools/piper/piper" ]; then
  echo "== Downloading Piper =="
  mkdir -p tools/piper-download
  PIPER_URL="https://github.com/rhasspy/piper/releases/latest/download/piper_linux_x86_64.tar.gz"
  wget -q -O tools/piper-download/piper.tar.gz "$PIPER_URL"
  tar -xzf tools/piper-download/piper.tar.gz -C tools/piper-download
  mv tools/piper-download/piper tools/piper
  rm -rf tools/piper-download
  chmod +x tools/piper/piper
  echo "Piper binary ready at tools/piper/piper"
else
  echo "Piper binary already present, skipping."
fi

if [ ! -f "tools/piper/voices/en_US-lessac-medium.onnx" ]; then
  echo "== Downloading a Piper voice (en_US-lessac-medium) =="
  mkdir -p tools/piper/voices
  BASE="https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium"
  wget -q -O tools/piper/voices/en_US-lessac-medium.onnx "$BASE/en_US-lessac-medium.onnx"
  wget -q -O tools/piper/voices/en_US-lessac-medium.onnx.json "$BASE/en_US-lessac-medium.onnx.json"
  echo "Voice model ready."
else
  echo "Piper voice already present, skipping."
fi

echo
echo "All done. assistant.voice.enabled is already true by default - just (re)start the app:"
echo "  ./scripts/run.sh"
echo "Check GET /api/voice/status to confirm, or click the mic button in the UI."
