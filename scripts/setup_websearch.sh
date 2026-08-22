#!/usr/bin/env bash
# One-time setup for KEYLESS live web search (DuckDuckGo).
#
# No API key, no account, no credit card — unlike Tavily. Creates a small Python venv under
# ./tools/websearch-venv (git-ignored) with the `ddgs` library, which scripts/ddg_search.py uses.
#
# Why a venv rather than a system pip install: modern Debian/Ubuntu mark the system Python as
# externally-managed (PEP 668), so `pip install` there is refused. A venv sidesteps that without
# needing root.
#
# After this finishes, restart the app — DuckDuckGoSearchTool detects the venv automatically and
# "Live web search" turns green in the sidebar. No config change required.
set -euo pipefail
cd "$(dirname "$0")/.."

VENV="tools/websearch-venv"

if [ -x "$VENV/bin/python" ] && "$VENV/bin/python" -c "import ddgs" 2>/dev/null; then
  echo "Keyless web search already set up at $VENV — nothing to do."
  exit 0
fi

echo "== Creating Python venv at $VENV =="
mkdir -p tools
python3 -m venv "$VENV"

echo "== Installing ddgs (DuckDuckGo search client) =="
"$VENV/bin/pip" install --quiet --upgrade pip
"$VENV/bin/pip" install --quiet ddgs

echo "== Verifying =="
"$VENV/bin/python" scripts/ddg_search.py "hello world" > /dev/null && echo "Search bridge works."

echo
echo "Done. Restart the app and 'Live web search' will show as enabled (DuckDuckGo)."
echo "  ./scripts/run.sh"
