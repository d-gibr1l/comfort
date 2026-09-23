"""Fork server: pays for importing the engines once, then runs each wrapper job in a fork()ed copy.

Started by PythonRuntime.kt. Every job used to be a fresh interpreter that re-imported yt-dlp
(~0.5s warm, 2.4-3.1s cold on-device) before doing any work; here a job starts from a copy of an
already-warm interpreter instead. Each job is still its own process with its own memory, so one
job's monkeypatches/globals can't leak into the next, exactly like the old one-process-per-job.

Protocol (Unix socket at argv[1], in the app's private no_backup dir; the peer must be our uid):
  client -> server: one JSON line {"script": "<wrapper>.py", "args": [...]}
  server -> client: "\\x01pid <pid>", then the wrapper's own stdout+stderr, then "\\x01exit <code>".
EOF without an exit line means the job died (killed by Pause/Cancel, or a native crash).
The job runs in its own process group, so the app can kill it together with its ffmpeg/aria2c.

The server exits when its stdin closes (the app process died) or after IDLE_SECONDS without jobs.
"""
import gc
import json
import os
import select
import signal
import socket
import struct
import sys
import time

IDLE_SECONDS = 10 * 60
CONTROL = "\x01"

# What the jobs import. Anything missing (a broken engine update) just isn't preloaded; the job
# itself then imports it, or fails with the same error it would have without the server.
_PRELOAD = (
    "yt_dlp", "yt_dlp.networking.impersonate", "yt_dlp.postprocessor.common",
    "yt_dlp.postprocessor.ffmpeg", "yt_dlp.postprocessor.metadataparser", "yt_dlp.utils",
    "gallery_dl", "gallery_dl.job", "gallery_dl.output",
    "instaloader",
    "json", "ssl", "urllib.request", "importlib.util", "contextlib", "shlex",
)

_code_cache = {}


def _load(path):
    mtime = os.stat(path).st_mtime_ns
    cached = _code_cache.get(path)
    if cached and cached[0] == mtime:
        return cached[1]
    with open(path, "rb") as f:
        code = compile(f.read(), path, "exec")
    _code_cache[path] = (mtime, code)
    return code


def _run_job(conn, script, args):
    """In the forked child. Never returns."""
    code = 1
    try:
        os.setpgid(0, 0)
        signal.signal(signal.SIGCHLD, signal.SIG_DFL)
        os.write(conn.fileno(), f"{CONTROL}pid {os.getpid()}\n".encode())
        devnull = os.open(os.devnull, os.O_RDONLY)
        os.dup2(devnull, 0)
        os.close(devnull)
        # sys.stdout/sys.stderr keep their objects (and whatever a preloaded module captured); only
        # the fds under them move to the socket. Their buffers are empty here (the server flushes
        # after every write of its own).
        os.dup2(conn.fileno(), 1)
        os.dup2(conn.fileno(), 2)
        conn.close()

        import random
        import types
        random.seed()  # otherwise every job would replay the server's random sequence

        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), script)
        sys.argv = [path] + list(args)
        main = types.ModuleType("__main__")
        main.__file__ = path
        main.__builtins__ = __builtins__
        sys.modules["__main__"] = main
        try:
            exec(_load(path), main.__dict__)
            code = 0
        except SystemExit as e:
            if e.code is None:
                code = 0
            elif isinstance(e.code, int):
                code = e.code
            else:
                print(e.code, file=sys.stderr)
                code = 1
        except BaseException:
            import traceback
            traceback.print_exc()
            code = 1
        # What a normal interpreter exit would still do.
        try:
            import threading
            threading._shutdown()
        except BaseException:
            pass
        try:
            import atexit
            atexit._run_exitfuncs()
        except BaseException:
            pass
        for stream in (sys.stdout, sys.stderr):
            try:
                stream.flush()
            except BaseException:
                pass
        os.write(1, f"{CONTROL}exit {code}\n".encode())
    except BaseException:
        pass
    finally:
        os._exit(code)


def _peer_uid(conn):
    creds = conn.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, struct.calcsize("3i"))
    return struct.unpack("3i", creds)[1]


def _read_request(conn):
    conn.settimeout(5)
    data = b""
    while not data.endswith(b"\n"):
        chunk = conn.recv(65536)
        if not chunk:
            raise EOFError
        data += chunk
    conn.settimeout(None)
    return json.loads(data)


def main():
    sock_path = sys.argv[1]
    for name in _PRELOAD:
        try:
            __import__(name)
        except BaseException:
            pass
    try:
        # gallery-dl imports its ~300 site modules lazily on the first URL match (~150ms per job).
        import gallery_dl.extractor
        gallery_dl.extractor.extractors()
    except BaseException:
        pass
    for name in ("gallery_dl_wrapper.py", "yt_dlp_wrapper.py", "spotify_wrapper.py", "instaloader_wrapper.py"):
        try:
            _load(os.path.join(os.path.dirname(os.path.abspath(__file__)), name))
        except BaseException:
            pass

    try:
        os.unlink(sock_path)
    except FileNotFoundError:
        pass
    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    server.bind(sock_path)
    server.listen(16)

    signal.signal(signal.SIGCHLD, signal.SIG_IGN)  # jobs are reaped automatically
    # Everything allocated so far stays shared with the jobs instead of being copied into each one
    # the first time the garbage collector touches it.
    gc.collect()
    gc.freeze()

    print(f"{CONTROL}ready", flush=True)
    last_job = time.monotonic()
    while True:
        timeout = IDLE_SECONDS - (time.monotonic() - last_job)
        if timeout <= 0:
            break
        readable, _, _ = select.select([server, 0], [], [], timeout)
        if 0 in readable and not os.read(0, 4096):
            break  # the app is gone
        if server not in readable:
            continue
        conn, _ = server.accept()
        try:
            if _peer_uid(conn) != os.getuid():
                raise PermissionError
            request = _read_request(conn)
            script, args = request["script"], request["args"]
            if not (isinstance(script, str) and script.endswith("_wrapper.py") and "/" not in script
                    and isinstance(args, list) and all(isinstance(a, str) for a in args)):
                raise ValueError
        except BaseException:
            conn.close()
            continue
        last_job = time.monotonic()
        if os.fork() == 0:
            server.close()
            _run_job(conn, script, args)
        conn.close()

    server.close()
    try:
        os.unlink(sock_path)
    except OSError:
        pass


if __name__ == "__main__":
    main()
