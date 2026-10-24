"""The Settings > Folders filename format, for the engines that don't speak it.

The format is gallery-dl's template syntax ("{poster_name|category}... [{short_id|filename}].{extension}");
gallery-dl applies it itself. yt-dlp and Instaloader name their files here instead, through
gallery-dl's own formatter (installed alongside them), from their own details put under
gallery-dl's field names — so the same format, "{category}" for the site included, names every
download whichever engine fetched it.
"""
import os
import re

# The format's own ".{extension}" ending: the engines add the real extension themselves (yt-dlp's
# may change after merging or converting).
_EXTENSION_SUFFIX = re.compile(r"\.\{extension[^{}]*\}\s*$")


def render(fmt, fields):
    """[fmt] filled in from [fields], without its ".{extension}" ending; None if it can't be (the
    engine then names the file its usual way). A missing field reads "None", as in gallery-dl. An
    empty value must be "" rather than None: gallery-dl's conversions (!W...) fail on None."""
    try:
        from gallery_dl import formatter
        body = _EXTENSION_SUFFIX.sub("", fmt or "")
        if not body.strip():
            return None
        values = {k: ("" if v is None else v) for k, v in fields.items()}
        name = formatter.parse(body).format_map(values)
    except Exception:  # noqa: BLE001 — a bad template falls back to the default name
        return None
    # A file name, not a path: a "/" in a field would make folders.
    name = re.sub(r"[\\/]+", "_", name).strip().rstrip(".")
    return name or None


# A file's name, extension included, in bytes at most. Android's limit is 255, and the engines'
# temporary names add up to ~90 to the one they work out: yt-dlp's ".f<format id>" for each format
# of a merge, ".part", "-Frag<n>.part" for each fragment of a stream; gallery-dl's ".part";
# Instaloader's ".temp". A post captioned in Japanese (3-4 bytes a character) came to a 212-byte
# name; its fragments' names, 257 bytes, couldn't be created ("[Errno 36] File name too long",
# seen live), and the download failed as empty.
NAME_MAX_BYTES = 165

# The " [id]" / " [site]" tags a name ends with (and a chapter's " - 001 title" after them): kept
# whole, so a shortened name still ends like the others.
_NAME_TAGS = re.compile(r" \[[^\[\]]*\].*$")


def fit_name(name, max_bytes=NAME_MAX_BYTES):
    """[name] (a file's name with its extension, not its folder) shortened to [max_bytes]: the text
    before its tags is cut, at a character, and marked "…"; the tags and extension stay. A name
    with no tags (or tags too long to keep) is cut at its end instead. A name that's all ASCII (as
    "Restrict filenames" makes them) is marked "..." instead, keeping it ASCII."""
    if len(name.encode("utf-8")) <= max_bytes:
        return name
    mark = "..." if name.isascii() else "…"
    stem, ext = os.path.splitext(name)
    if len(ext) > 12:  # not a real extension ("… 2.5 hours"), part of the name
        stem, ext = name, ""
    match = _NAME_TAGS.search(stem)
    tail = match.group(0) if match and match.start() > 0 else ""
    budget = max_bytes - len((mark + tail + ext).encode("utf-8"))
    if budget < 20:
        tail, budget = "", max_bytes - len((mark + ext).encode("utf-8"))
    head = stem[:len(stem) - len(tail)]
    head = head.encode("utf-8")[:budget].decode("utf-8", "ignore").rstrip(" -–—_.")
    return f"{head}{mark}{tail}{ext}"


def site_of(url):
    """The site's name from a link — "youtube" for https://www.youtube.com/... — for "{category}"
    when the engine has nothing better."""
    m = re.match(r"https?://([^/:?#]+)", url or "")
    if not m:
        return ""
    host = m.group(1).lower()
    for prefix in ("www.", "m.", "mobile.", "music."):
        if host.startswith(prefix):
            host = host[len(prefix):]
    parts = host.split(".")
    # example.co.uk, example.com.au: the name is before the two-part ending.
    if len(parts) >= 3 and parts[-2] in ("co", "com", "net", "org", "ac", "gov", "or", "ne"):
        return parts[-3]
    return parts[-2] if len(parts) >= 2 else host
