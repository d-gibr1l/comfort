"""Lists the sites each engine supports, for Settings › Updates and engines › Sites.

Prints one JSON object: {"gallery_dl": [...], "yt_dlp": [...], "versions": {...}} — each list is
site domains ("pixiv.net", "youtube.com"), deduplicated and sorted. Read from the engines
themselves, so an engine update changes the lists:
- gallery-dl: every extractor class's example URL (or its root), plus each multi-site extractor's
  instances (Mastodon servers, booru sites, ...).
- yt-dlp: every extractor's _VALID_URL pattern, from which the literal domain names are pulled
  (the patterns spell them out, escaped: "youtube\\.com"). The generic extractor is skipped.
These are approximations (a pattern can name several domains, a few name none), good enough to
browse and search.
"""
import json
import re
import sys
from urllib.parse import urlparse

# Second-level labels that come before a two-letter country code ("bbc.co.uk", "uol.com.br").
_SECOND_LEVEL = {"co", "com", "net", "org", "gov", "ac", "edu", "ne", "or", "go", "gob", "nic"}
_DOMAIN_IN_PATTERN = re.compile(r"(?<![\w\\])((?:[a-z0-9][a-z0-9-]*\\\.)+[a-z]{2,}(?![a-z0-9-]))", re.I)
# A small group of literal alternatives, optionally optional: "(?:twitter|x)", "(?:media)?".
_SIMPLE_GROUP = re.compile(r"\((?:\?P<\w+>|\?:)([a-z0-9|\\.-]*)\)(\?)?", re.I)
# Things a URL pattern spells out that aren't sites: file names, and Tor addresses.
_NOT_A_TLD = {
    "html", "htm", "shtml", "php", "asp", "aspx", "ashx", "jsp", "cgi", "do", "action", "json", "js",
    "xml", "txt", "mp4", "mp3", "m3u8", "mpd", "f4m", "smil", "ism", "swf", "flv", "png", "jpg",
    "jpeg", "gif", "webp", "embed", "onion", "local", "invalid", "example",
}


def site_of_host(host):
    host = (host or "").lower().strip(".")
    if not host or "." not in host or host.replace(".", "").isdigit():
        return None
    if host.rsplit(".", 1)[-1] in _NOT_A_TLD:
        return None
    parts = host.split(".")
    if len(parts) > 2:
        keep = 3 if parts[-2] in _SECOND_LEVEL and len(parts[-1]) == 2 else 2
        parts = parts[-keep:]
    return ".".join(parts)


def site_of_url(url):
    try:
        return site_of_host(urlparse(url).hostname)
    except Exception:  # noqa: BLE001
        return None


def gallery_dl_sites():
    from gallery_dl import extractor
    sites = set()
    for cls in extractor._list_classes():
        for attr in ("example", "root"):
            value = getattr(cls, attr, None)
            if isinstance(value, str) and "://" in value:
                s = site_of_url(value)
                if s:
                    sites.add(s)
                    break
        for instance in getattr(cls, "instances", None) or ():
            root = instance.get("root") if isinstance(instance, dict) else None
            if root:
                s = site_of_url(root)
                if s:
                    sites.add(s)
    return sites


def _expand(pattern, limit=64):
    r"""[pattern] with its small literal-alternative groups spelled out, one string per
    combination ("(?:twitter|x)\.com" -> "twitter\.com", "x\.com"); at most [limit]."""
    out, todo = [], [pattern]
    while todo and len(out) < limit:
        p = todo.pop()
        m = _SIMPLE_GROUP.search(p)
        if not m:
            out.append(p)
            continue
        options = m.group(1).split("|") + ([""] if m.group(2) else [])
        for option in options:
            if len(todo) + len(out) < limit:
                todo.append(p[:m.start()] + option + p[m.end():])
    return out


def yt_dlp_sites():
    from yt_dlp.extractor import gen_extractor_classes
    sites = set()
    for ie in gen_extractor_classes():
        if ie.ie_key() == "Generic":
            continue
        patterns = getattr(ie, "_VALID_URL", None)
        if isinstance(patterns, str):
            patterns = [patterns]
        for pattern in patterns or ():
            if not isinstance(pattern, str):
                continue
            for variant in _expand(pattern):
                for raw in _DOMAIN_IN_PATTERN.findall(variant):
                    s = site_of_host(raw.replace("\\.", "."))
                    if s:
                        sites.add(s)
    return sites


def main():
    out = {"gallery_dl": [], "yt_dlp": [], "versions": {}}
    try:
        out["gallery_dl"] = sorted(gallery_dl_sites())
        from gallery_dl import version as gv
        out["versions"]["gallery_dl"] = gv.__version__
    except Exception as e:  # noqa: BLE001
        out["gallery_dl_error"] = str(e)
    try:
        out["yt_dlp"] = sorted(yt_dlp_sites())
        from yt_dlp import version as yv
        out["versions"]["yt_dlp"] = yv.__version__
    except Exception as e:  # noqa: BLE001
        out["yt_dlp_error"] = str(e)
    print(json.dumps(out), flush=True)


if __name__ == "__main__":
    main()
