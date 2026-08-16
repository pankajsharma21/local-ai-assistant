#!/usr/bin/env bash
# One-time setup for the voice feature: builds whisper.cpp (local speech-to-text) and downloads
# Piper (local text-to-speech) + small voice models. Everything ends up under ./tools, which is
# git-ignored on purpose (these are large binaries, not source).
#
# Prerequisites: git, make, a C/C++ compiler, and EITHER cmake (recommended) OR the legacy
# whisper.cpp plain-Makefile build. Install with e.g.:
#   sudo apt install -y build-essential cmake git
#
# After this finishes successfully, set assistant.voice.enabled: true in application.yml
# (the default paths in application.yml already point at ./tools/...).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p tools

# ---------------------------------------------------------------------------
# 1. whisper.cpp (speech-to-text)
# ---------------------------------------------------------------------------
if [ ! -x "tools/whisper/main" ]; then
  echo "== Building whisper.cpp =="
  if [ ! -d "tools/whisper-src" ]; then
    git clone --depth 1 https://github.com/ggerganov/whisper.cpp.git tools/whisper-src
  fi
  cd tools/whisper-src

  BUILT_BIN=""
  if command -v cmake >/dev/null 2>&1; then
    cmake -B build
    cmake --build build -j --config Release
    [ -x "build/bin/whisper-cli" ] && BUILT_BIN="build/bin/whisper-cli"
    [ -z "$BUILT_BIN" ] && [ -x "build/bin/main" ] && BUILT_BIN="build/bin/main"
  fi
  if [ -z "$BUILT_BIN" ]; then
    echo "cmake not found or build didn't produce a binary — trying the legacy 'make' build..."
    make -j
    [ -x "main" ] && BUILT_BIN="main"
  fi
  if [ -z "$BUILT_BIN" ]; then
    echo "ERROR: could not build whisper.cpp automatically. Build it manually and copy the"
    echo "resulting CLI binary to tools/whisper/main (see https://github.com/ggerganov/whisper.cpp)."
    exit 1
  fi

  # Small English model — good accuracy/speed tradeoff on CPU. Swap for base/small/medium as needed.
  bash ./models/download-ggml-model.sh base.en

  mkdir -p ../whisper/models
  cp "$BUILT_BIN" ../whisper/main
  cp models/ggml-base.en.bin ../whisper/models/
  cd ../..
  echo "whisper.cpp ready at tools/whisper/main"
else
  echo "whisper.cpp already set up, skipping."
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
echo "All done. Now set assistant.voice.enabled: true in src/main/resources/application.yml"
echo "and restart the app. Check GET /api/voice/status to confirm."
