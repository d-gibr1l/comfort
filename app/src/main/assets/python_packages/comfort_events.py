"""Structured report lines the wrappers send to the app (DownloadWorker / GalleryDlListing).

Each report is one line: ASCII record separator (0x1E) followed by a JSON object with a "type"
field — read on the Kotlin side by EngineEventParser. The marker tells a report apart from anything
the engines' own loggers print on the same stdout, and JSON carries any text (a title with a
newline, an error with a traceback) without a parser having to guess where a value ends.

Types: size{bytes} total{count} phase{phase} format{tags} progress{downloaded, speed}
thumbnail{url} title{title} artist{artist} album{album} track{track} error{message} file{path}
warning{message} debug{message} status{message} exit{status}
"""
import json

MARK = "\x1e"


def event(type_, **fields):
    """The line for one report, e.g. event("size", bytes=1234)."""
    return MARK + json.dumps({"type": type_, **fields}, ensure_ascii=False, separators=(",", ":"), default=str)


def parse(line):
    """The report a line carries, as a dict — None for anything else (an engine's own log text)."""
    if not isinstance(line, str) or not line.startswith(MARK):
        return None
    try:
        report = json.loads(line[len(MARK):])
    except ValueError:
        return None
    return report if isinstance(report, dict) else None


def file_path(line):
    """The saved file a "file" report names, else None."""
    report = parse(line)
    return report.get("path") if report and report.get("type") == "file" else None
