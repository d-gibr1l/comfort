"""Optional, network-based check of yt_dlp_wrapper.py's `latest` command (Subscriptions' "what's
new" listing) against a few fixed public URLs: one JSON line on stdout, the agreed shape, at most
`limit` entries, YouTube ids/links well formed, and how long each took.

Needs yt-dlp importable by the Python that runs this (`pip install -U yt-dlp`). Runs the wrapper
with -B so no __pycache__ lands under app/src/main/assets/python_packages.

    python tools/test_latest_entries.py
"""
import json
import os
import re
import subprocess
import sys
import time

WRAPPER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets",
                       "python_packages", "yt_dlp_wrapper.py")

# (label, url, limit, expect_error)
CASES = [
    ("channel @handle", "https://www.youtube.com/@veritasium", "", False),
    ("channel /videos", "https://www.youtube.com/@veritasium/videos", "", False),
    ("channel /channel/UC", "https://www.youtube.com/channel/UCHnyfMqiRRG1u-2MsSQLbXA", "5", False),
    ("playlist", "https://www.youtube.com/playlist?list=PLFs4vir_WsTwEd-nJgVJCZPNL3HALHHpF", "", False),
    ("limit=3", "https://www.youtube.com/@veritasium", "3", False),
    ("invalid url", "not a url at all", "", True),
    ("missing channel", "https://www.youtube.com/@this-channel-does-not-exist-zz9q", "", True),
]

YT_ID = re.compile(r"^[\w-]{11}$")


def run(url, limit):
    start = time.monotonic()
    proc = subprocess.run([sys.executable, "-B", WRAPPER, "latest", url, limit, "", ""],
                          capture_output=True, text=True, encoding="utf-8", timeout=120)
    return proc, time.monotonic() - start


def check(label, url, limit, expect_error):
    proc, took = run(url, limit)
    lines = [l for l in proc.stdout.splitlines() if l.strip()]
    problems = []
    if proc.returncode != 0:
        problems.append(f"exit code {proc.returncode}")
    if len(lines) != 1:
        problems.append(f"{len(lines)} stdout lines, expected 1")
    try:
        data = json.loads(lines[-1]) if lines else {}
    except ValueError:
        data = {}
        problems.append("last line isn't JSON")
    if expect_error:
        if not isinstance(data.get("error"), str):
            problems.append("expected an error object")
    elif "error" in data:
        problems.append(f"error: {data['error']}")
    else:
        for key in ("title", "webpage_url"):
            if not isinstance(data.get(key), str):
                problems.append(f"{key} isn't a string")
        entries = data.get("entries")
        cap = int(limit) if limit else 30
        if not isinstance(entries, list) or not entries:
            problems.append("no entries")
            entries = []
        if len(entries) > cap:
            problems.append(f"{len(entries)} entries > limit {cap}")
        for e in entries:
            if "youtube.com" in url and not (YT_ID.match(e.get("id") or "")
                                             and e.get("url") == f"https://www.youtube.com/watch?v={e['id']}"):
                problems.append(f"bad id/url: {e.get('id')} {e.get('url')}")
            d = e.get("upload_date")
            if d is not None and not re.match(r"^\d{8}$", d):
                problems.append(f"bad upload_date {d}")
        dates = [e["upload_date"] for e in entries if e.get("upload_date")]
        if "playlist?" not in url and dates != sorted(dates, reverse=True):
            problems.append("channel entries not newest first")
    status = "FAIL" if problems else "ok"
    summary = data.get("error") or f"{data.get('title')!r}, {len(data.get('entries') or [])} entries"
    print(f"[{status}] {label}: {took:.1f}s — {summary}")
    for p in problems:
        print(f"       {p}")
    return not problems


if __name__ == "__main__":
    results = [check(*case) for case in CASES]
    sys.exit(0 if all(results) else 1)
