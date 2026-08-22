#!/usr/bin/env python3
"""
Keyless web search bridge for DuckDuckGoSearchTool.java.

Reads a query as argv[1], prints a JSON array of {title, url, snippet} to stdout, and exits 0.
On failure prints {"error": "..."} and exits 1 so the Java side can fall back cleanly.

Why a Python helper instead of doing this in Java: DuckDuckGo actively blocks naive HTML scraping
(verified - raw requests to html.duckduckgo.com come back as an anti-bot challenge page with zero
parseable results). The `ddgs` library maintains the header/backend/parsing workarounds for that.
Reimplementing it in Java would mean owning that cat-and-mouse game ourselves. This project already
shells out to external tools for whisper.cpp and Piper, so the pattern is consistent.
"""
import json
import sys

MAX_RESULTS = 5
SNIPPET_CHARS = 400


def main() -> int:
    if len(sys.argv) < 2 or not sys.argv[1].strip():
        print(json.dumps({"error": "no query given"}))
        return 1
    query = sys.argv[1].strip()

    try:
        from ddgs import DDGS
    except ImportError as exc:
        print(json.dumps({"error": f"ddgs not installed in this interpreter: {exc}"}))
        return 1

    try:
        with DDGS() as ddgs:
            raw = list(ddgs.text(query, max_results=MAX_RESULTS))
    except Exception as exc:  # noqa: BLE001 - any failure should degrade to the Java fallback
        print(json.dumps({"error": f"{type(exc).__name__}: {exc}"}))
        return 1

    results = [
        {
            "title": (item.get("title") or "").strip(),
            "url": (item.get("href") or "").strip(),
            "snippet": (item.get("body") or "").strip()[:SNIPPET_CHARS],
        }
        for item in raw
    ]
    print(json.dumps(results, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
