"""The Settings > Folders filename format, for the engines that don't speak it.

The format is gallery-dl's template syntax ("{poster_name|category}... [{short_id|filename}].{extension}");
gallery-dl applies it itself. yt-dlp and Instaloader name their files here instead, through
gallery-dl's own formatter (installed alongside them), from their own details put under
gallery-dl's field names — so the same format, "{category}" for the site included, names every
download whichever engine fetched it.
"""
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
