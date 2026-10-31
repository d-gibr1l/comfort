"""Offline check that a stopped yt-dlp download picks up where it stopped, through yt_dlp_wrapper.py,
against a local throttled HTTP server — so a yt-dlp update (Settings › Updates and engines) or a
wrapper change can't quietly break resuming again:

- killed mid-file, the next run asks for the rest only (a Range request), not the whole file;
- the same when the next run would pick another stream (_resume_format, `.comfort-formats`);
- stopped after converting (MP3), it isn't downloaded again (final_ext);
- stopped mid-conversion, the conversion is redone from the original, not downloaded again, and
  the result is whole (_DropUnfinishedConversionPP);
- yt-dlp's retries pause 1, 2, 4, 8, 16 then 30 s (_retry_sleep).

Needs yt-dlp importable by the Python that runs this (`pip install -U yt-dlp`); the conversion
checks also need ffmpeg/ffprobe on PATH and are skipped without them. No network. Runs the wrapper
in child processes with bytecode writing off, so no __pycache__ lands in the app's assets.

    python tools/test_wrapper_resume.py
"""
import http.server
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest

WRAPPER_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets",
                           "python_packages")

# Runs one wrapper download in its own process (so it can be killed like the app kills a job):
# argv = wrapper dir, staging dir, JSON keyword arguments (with "url").
RUNNER = r"""
import json, sys
sys.dont_write_bytecode = True
sys.path.insert(0, sys.argv[1])
import yt_dlp_wrapper as w
kw = json.loads(sys.argv[3])
w.download(kw.pop("url"), sys.argv[2], callback=lambda line: print(line, flush=True), **kw)
"""

try:
    import yt_dlp  # noqa: F401
    HAVE_YT_DLP = True
except ImportError:
    HAVE_YT_DLP = False
HAVE_FFMPEG = bool(shutil.which("ffmpeg") and shutil.which("ffprobe"))


class _Server:
    """Serves in-memory files with Range support; [throttle] paths trickle out (~3 MB/s) so a run
    can be killed mid-file. Records every request as (method, path, Range header)."""

    def __init__(self):
        self.files, self.throttle, self.requests = {}, set(), []
        outer = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_HEAD(self):
                self._send(False)

            def do_GET(self):
                self._send(True)

            def _send(self, body):
                rng = self.headers.get("Range")
                outer.requests.append((self.command, self.path, rng))
                data = outer.files.get(self.path)
                if data is None:
                    self.send_response(404)
                    self.end_headers()
                    return
                start = int(re.match(r"bytes=(\d+)-", rng).group(1)) if rng else 0
                self.send_response(206 if rng else 200)
                if rng:
                    self.send_header("Content-Range", f"bytes {start}-{len(data) - 1}/{len(data)}")
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Length", str(len(data) - start))
                self.end_headers()
                if not body:
                    return
                try:
                    step = 64 * 1024
                    for i in range(start, len(data), step):
                        self.wfile.write(data[i:i + step])
                        if self.path in outer.throttle:
                            time.sleep(0.02)
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def log_message(self, *args):
                pass

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def gets(self):
        return [r for r in self.requests if r[0] == "GET"]

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


@unittest.skipUnless(HAVE_YT_DLP, "yt-dlp isn't importable (pip install -U yt-dlp)")
class WrapperResumeTest(unittest.TestCase):

    def setUp(self):
        self.server = _Server()
        self.tmp = tempfile.mkdtemp(prefix="comfort-resume-")
        self.stage = os.path.join(self.tmp, "stage")
        os.makedirs(self.stage)

    def tearDown(self):
        self.server.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def info_json(self, video_id, formats, duration=None):
        """A saved extraction (as the preview sheet leaves one) the wrapper downloads from."""
        path = os.path.join(self.tmp, f"info-{video_id}.json")
        info = {"id": video_id, "title": "clip", "extractor": "generic", "extractor_key": "Generic",
                "_type": "video", "webpage_url": self.server.base + "/page", "formats": formats}
        if duration:
            info["duration"] = duration
        with open(path, "w") as f:
            json.dump(info, f)
        return path

    def run_wrapper(self, kill_when_part_bytes=None, **kw):
        """One download; with [kill_when_part_bytes], killed once a .part file has that much."""
        kw.setdefault("url", self.server.base + "/page")
        proc = subprocess.Popen([sys.executable, "-B", "-c", RUNNER, WRAPPER_DIR, self.stage, json.dumps(kw)],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        if kill_when_part_bytes is None:
            out, _ = proc.communicate(timeout=180)
            return out
        deadline = time.time() + 60
        while time.time() < deadline:
            parts = [f for f in os.listdir(self.stage) if f.endswith(".part")]
            if parts and os.path.getsize(os.path.join(self.stage, parts[0])) >= kill_when_part_bytes:
                break
            if proc.poll() is not None:
                self.fail("the download finished before it could be stopped: " + proc.stdout.read()[-2000:])
            time.sleep(0.05)
        else:
            proc.kill()
            self.fail("no partial file appeared")
        proc.kill()
        proc.wait()
        proc.stdout.close()
        return None

    def finished(self, ext):
        return [f for f in os.listdir(self.stage) if f.endswith("." + ext) and not f.endswith(".part")]

    # --- Resume ---

    def test_killed_mid_file_asks_for_the_rest(self):
        data = os.urandom(8 * 1024 * 1024)
        self.server.files["/a.mp4"] = data
        self.server.throttle.add("/a.mp4")
        info = self.info_json("v1", [{"format_id": "a", "url": self.server.base + "/a.mp4", "ext": "mp4",
                                      "vcodec": "h264", "acodec": "aac"}])
        self.run_wrapper(kill_when_part_bytes=1024 * 1024, info_json_path=info)
        self.server.requests.clear()
        self.server.throttle.clear()
        out = self.run_wrapper(info_json_path=info)
        ranges = [r[2] for r in self.server.gets() if r[2]]
        self.assertTrue(ranges, "the second run asked for the whole file again:\n" + out[-2000:])
        self.assertIn("Resuming download", out)
        done = self.finished("mp4")
        self.assertEqual(len(done), 1, os.listdir(self.stage))
        with open(os.path.join(self.stage, done[0]), "rb") as f:
            self.assertEqual(f.read(), data, "the resumed file isn't the original")

    def test_next_run_picking_another_stream_still_resumes(self):
        self.server.files["/a.mp4"] = os.urandom(8 * 1024 * 1024)
        self.server.files["/b.webm"] = os.urandom(8 * 1024 * 1024)
        self.server.throttle.update({"/a.mp4", "/b.webm"})
        formats = [{"format_id": "fa", "url": self.server.base + "/a.mp4", "ext": "mp4", "vcodec": "h264", "acodec": "aac"},
                   {"format_id": "fb", "url": self.server.base + "/b.webm", "ext": "webm", "vcodec": "vp9", "acodec": "opus"}]
        info = self.info_json("v2", formats)
        self.run_wrapper(kill_when_part_bytes=1024 * 1024, info_json_path=info, format_selector="fa")
        self.server.requests.clear()
        self.server.throttle.clear()
        # A fresh extraction picking another stream, as can happen once the saved one has expired.
        out = self.run_wrapper(info_json_path=info, format_selector="fb")
        paths = {r[1] for r in self.server.gets()}
        self.assertEqual(paths, {"/a.mp4"}, "it didn't go back to the stream its partial file is from:\n" + out[-2000:])
        self.assertIn("Resuming download", out)
        self.assertTrue(self.finished("mp4"))
        self.assertFalse(self.finished("webm"))

    # --- Conversion ---

    def song(self, video_id):
        """A real 60 s Opus file to convert, served as-is (no need to stop it mid-file)."""
        src = os.path.join(self.tmp, "song.webm")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=60",
                        "-c:a", "libopus", "-b:a", "64k", src], check=True)
        with open(src, "rb") as f:
            self.server.files["/song.webm"] = f.read()
        return self.info_json(video_id, [{"format_id": "a", "url": self.server.base + "/song.webm", "ext": "webm",
                                          "vcodec": "none", "acodec": "opus"}], duration=60)

    def as_mp3(self, info):
        return dict(info_json_path=info, audio_only=True, audio_format="mp3", ffmpeg_path=shutil.which("ffmpeg"))

    def duration(self, path):
        out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                             capture_output=True, text=True, check=True).stdout
        return float(out.strip())

    @unittest.skipUnless(HAVE_FFMPEG, "ffmpeg/ffprobe not on PATH")
    def test_stopped_after_converting_is_not_downloaded_again(self):
        info = self.song("s1")
        self.run_wrapper(**self.as_mp3(info))
        self.assertTrue(self.finished("mp3"))
        self.assertFalse(self.finished("webm"), "the original should be gone after converting")
        self.server.requests.clear()
        out = self.run_wrapper(**self.as_mp3(info))
        self.assertEqual(self.server.gets(), [], "it downloaded again:\n" + out[-2000:])
        self.assertIn("has already been downloaded", out)

    @unittest.skipUnless(HAVE_FFMPEG, "ffmpeg/ffprobe not on PATH")
    def test_stopped_mid_conversion_is_redone_from_the_original(self):
        info = self.song("s2")
        self.run_wrapper(**self.as_mp3(info))
        mp3 = os.path.join(self.stage, self.finished("mp3")[0])
        # What a stop mid-conversion leaves: the original back, next to a half-written MP3.
        with open(mp3, "rb") as f:
            whole = f.read()
        with open(mp3, "wb") as f:
            f.write(whole[:len(whole) // 10])
        original = os.path.splitext(mp3)[0] + ".webm"
        with open(original, "wb") as f:
            f.write(self.server.files["/song.webm"])
        self.server.requests.clear()
        out = self.run_wrapper(**self.as_mp3(info))
        self.assertEqual(self.server.gets(), [], "it downloaded again:\n" + out[-2000:])
        self.assertAlmostEqual(self.duration(mp3), 60, delta=1, msg="the redone MP3 isn't whole")

    # --- Retries ---

    def test_retry_pauses(self):
        sys.dont_write_bytecode = True
        sys.path.insert(0, WRAPPER_DIR)
        try:
            import yt_dlp_wrapper
        finally:
            sys.path.remove(WRAPPER_DIR)
        self.assertEqual([yt_dlp_wrapper._retry_sleep(n) for n in range(8)], [1, 2, 4, 8, 16, 30, 30, 30])


if __name__ == "__main__":
    unittest.main(verbosity=2)
