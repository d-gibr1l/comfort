"""Turns Android Lint's XML report into a GitHub check run's body ("Lint report" on the commit):
the counts by severity and issue, then every issue as "file:line: Severity: message [Id]", errors
first. Used by the lint job in .github/workflows/build.yml; prints the JSON for
`gh api repos/<repo>/check-runs --input -`.

    python tools/lint_check_run.py app/build/reports/lint-results-debug.xml <head sha>
"""
import collections
import json
import os
import sys
import xml.etree.ElementTree as ET

ORDER = {"Fatal": 0, "Error": 1, "Warning": 2, "Information": 3}
MAX_TEXT = 65000  # GitHub's limit for a check run's text is 65535 characters.


def main(report, sha):
    root = ET.parse(report).getroot() if os.path.exists(report) else None
    issues = []
    for issue in (root.findall("issue") if root is not None else []):
        loc = issue.find("location")
        path = loc.get("file", "") if loc is not None else ""
        path = path.split("/app/", 1)[-1] if "/app/" in path else path
        line = loc.get("line", "") if loc is not None else ""
        issues.append((ORDER.get(issue.get("severity"), 9), issue.get("severity"), issue.get("id"),
                       f"app/{path}:{line}" if path else "(project)", issue.get("message", "")))
    issues.sort()
    by_severity = collections.Counter(i[1] for i in issues)
    by_id = collections.Counter((i[1], i[2]) for i in issues)
    errors = by_severity.get("Error", 0) + by_severity.get("Fatal", 0)
    summary = ", ".join(f"{n} {s.lower()}" for s, n in sorted(by_severity.items(), key=lambda x: ORDER.get(x[0], 9))) or "no issues"
    if root is None:
        summary = "no lint report (the lint step failed before writing one)"
    counts = "\n".join(f"{n:5}  {s:8} {i}" for (s, i), n in sorted(by_id.items(), key=lambda x: (ORDER.get(x[0][0], 9), -x[1])))
    lines = "\n".join(f"{where}: {sev}: {msg} [{iid}]" for _, sev, iid, where, msg in issues)
    text = f"```\n{counts}\n```\n\n```\n{lines}\n```"
    if len(text) > MAX_TEXT:
        text = text[:MAX_TEXT - 40] + "\n…(cut)\n```"
    print(json.dumps({
        "name": "Lint report",
        "head_sha": sha,
        "status": "completed",
        "conclusion": "failure" if errors or root is None else "success",
        "output": {"title": summary, "summary": summary, "text": text},
    }))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
