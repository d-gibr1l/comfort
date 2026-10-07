"""Adds lyrics to a downloaded song: looks the song up on LRCLIB (lrclib.net — free, no key) by
the artist, title, album and length already in its tags, and embeds what it finds.

    lyrics.py <path> <synced|plain> <write_lrc 0|1> [title] [artist] [fallback_title] [fallback_artist] [seconds]

"synced" prefers time-stamped LRC lyrics (players that understand them scroll along with the
song; the rest show the text with its [mm:ss.xx] stamps) and falls back to plain text; "plain"
embeds plain text only. With write_lrc, synced mode also saves them to <song>.lrc beside the file, for
players that only read sidecars. title/artist override the tags (the preview sheet's edits);
the fallbacks and seconds stand in for tags the file doesn't have or can't hold — a .webm song
(some Spotify tracks), whose lyrics can only go in the .lrc file.

Prints one JSON line: {"lyrics": "synced" | "plain" | "none" | "instrumental", "lrc": bool}.
Never fails the download: no network, no match or an unreadable file just prints "none"."""

import json
import os
import re
import ssl
import sys
import urllib.parse
import urllib.request

_CACERT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cacert.pem")
_UA = "Comfort (https://github.com/d-gibr1l/comfort)"


def _tags(path):
    """(title, artist, album, seconds) from the file's own tags; any of them may be None."""
    import mutagen
    try:
        audio = mutagen.File(path, easy=True)
    except Exception:
        audio = None
    if audio is None:
        return None, None, None, None
    def first(key):
        v = audio.get(key) if audio.tags is not None else None
        return (v[0] if isinstance(v, list) and v else v) or None
    seconds = getattr(audio.info, "length", None)
    return first("title"), first("artist"), first("album"), (round(seconds) if seconds else None)


def _get(url):
    ctx = ssl.create_default_context(cafile=_CACERT) if os.path.exists(_CACERT) else None
    req = urllib.request.Request(url, headers={"User-Agent": _UA})
    try:
        with urllib.request.urlopen(req, timeout=10, context=ctx) as resp:
            return json.loads(resp.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def _artists(artist):
    """The tag as given, then just the first artist: YouTube Music tags every credited artist
    ("Djo, Joe Keery"), LRCLIB files the song under the main one."""
    first = re.split(r",|;| & | feat\.? | ft\.? | x ", artist, maxsplit=1, flags=re.I)[0].strip()
    return [artist] if not first or first == artist else [artist, first]


def _lookup(title, artist, album, seconds):
    for name in _artists(artist):
        hit = _lookup_one(title, name, album, seconds)
        if hit:
            return hit
    return None


def _lookup_one(title, artist, album, seconds):
    """LRCLIB's exact match first (it wants the length within a couple of seconds), then its search
    for the closest-length result — titles on YouTube rarely match a release exactly."""
    params = {"track_name": title, "artist_name": artist}
    if album:
        params["album_name"] = album
    if seconds:
        params["duration"] = seconds
    hit = _get("https://lrclib.net/api/get?" + urllib.parse.urlencode(params))
    if hit and (hit.get("syncedLyrics") or hit.get("plainLyrics") or hit.get("instrumental")):
        return hit
    results = _get("https://lrclib.net/api/search?" + urllib.parse.urlencode(
        {"track_name": title, "artist_name": artist})) or []
    results = [r for r in results if r.get("syncedLyrics") or r.get("plainLyrics")]
    if seconds:
        results = [r for r in results if abs((r.get("duration") or 0) - seconds) <= 3]
        results.sort(key=lambda r: (not r.get("syncedLyrics"), abs((r.get("duration") or 0) - seconds)))
    return results[0] if results else None


def _embed(path, text):
    ext = os.path.splitext(path)[1].lower()
    if ext in (".m4a", ".mp4", ".m4b", ".aac"):
        from mutagen.mp4 import MP4
        audio = MP4(path)
        if audio.tags is None:
            audio.add_tags()
        audio.tags["\xa9lyr"] = [text]
        audio.save()
    elif ext == ".mp3":
        from mutagen.id3 import ID3, USLT, ID3NoHeaderError
        try:
            tags = ID3(path)
        except ID3NoHeaderError:
            tags = ID3()
        tags.delall("USLT")
        tags.add(USLT(encoding=3, lang="eng", desc="", text=text))
        tags.save(path)
    else:
        # FLAC, Ogg/Opus and the like: Vorbis comments, where players read LYRICS.
        import mutagen
        audio = mutagen.File(path)
        if audio is None:
            raise ValueError("unsupported")
        if audio.tags is None:
            audio.add_tags()
        audio.tags["LYRICS"] = text
        audio.save()


def add_lyrics(path, mode="synced", write_lrc=True, title=None, artist=None,
               fallback_title=None, fallback_artist=None, fallback_seconds=None):
    result = {"lyrics": "none", "lrc": False}
    try:
        t, a, album, seconds = _tags(path)
        title, artist = title or t or fallback_title, artist or a or fallback_artist
        seconds = seconds or fallback_seconds
        if not title or not artist:
            return result
        hit = _lookup(title, artist, album, seconds)
        if not hit:
            return result
        if hit.get("instrumental") and not hit.get("plainLyrics"):
            result["lyrics"] = "instrumental"
            return result
        synced = (hit.get("syncedLyrics") or "").strip()
        plain = (hit.get("plainLyrics") or "").strip()
        text, kind = (synced, "synced") if mode == "synced" and synced else (plain, "plain")
        if not text:
            return result
        try:
            _embed(path, text)
            embedded = True
        except Exception:
            embedded = False  # a container mutagen can't tag (.webm): the .lrc file is all there is
        # Synced lyrics go to the .lrc in synced mode; a file that couldn't take them gets
        # whatever was found.
        lrc_text = synced if kind == "synced" else (None if embedded else text)
        if write_lrc and lrc_text:
            with open(os.path.splitext(path)[0] + ".lrc", "w", encoding="utf-8") as f:
                f.write(lrc_text + "\n")
            result["lrc"] = True
        if embedded or result["lrc"]:
            result["lyrics"] = kind
    except Exception:
        pass
    return result


if __name__ == "__main__":
    a = sys.argv[1:]
    if not a:
        print("Usage: lyrics.py <path> <synced|plain> <write_lrc 0|1> [title] [artist]", file=sys.stderr)
        sys.exit(2)
    print(json.dumps(add_lyrics(
        a[0],
        mode=a[1] if len(a) > 1 else "synced",
        write_lrc=(a[2] == "1") if len(a) > 2 else True,
        title=(a[3] or None) if len(a) > 3 else None,
        artist=(a[4] or None) if len(a) > 4 else None,
        fallback_title=(a[5] or None) if len(a) > 5 else None,
        fallback_artist=(a[6] or None) if len(a) > 6 else None,
        fallback_seconds=int(a[7]) if len(a) > 7 and a[7].isdigit() else None,
    )), flush=True)
