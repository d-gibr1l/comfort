"""Retries a stuck connect on a fresh connection instead of waiting out the whole timeout.

Measured on a real Wi-Fi network (2026-09-23): about half of new TCP connections got no answer at
all (still nothing after 8s) while the rest connected in 0.1-0.8s — to Google, Cloudflare,
YouTube's video servers and Instagram alike — and IPv6 never connected. The kernel's own SYN
retransmits on the *same* connection didn't help, but a new connection (new source port) usually
got straight through: the network was dropping particular connections, not random packets. The
engines' defaults wait the full socket timeout (20s, and curl was seen stuck 65s) on one attempt,
which showed up as downloads and previews "hanging". Here every connect is split into short
attempts on fresh sockets within the caller's own timeout. Call install() once, after the
engines are imported.

Measured on that network, 10 fresh connections each through requests: 233s and 293s stock (single
connects hanging 20-62s), 73s and 4.8s with this. curl_cffi (yt-dlp's impersonation path) is left
alone: libcurl already splits its connect timeout across addresses and races IPv4 against IPv6
(its stalls were 10-19s, not 20-62s), and shortening its attempts too made one request fail.
"""
import socket
import time

# Per-attempt connect timeouts in seconds; the last one repeats. A good connect took <1s on the
# bad network and ~2.5s at worst, so a 3s attempt only gives up on a connection that isn't coming.
_ATTEMPT_TIMEOUTS = (3.0, 4.0, 6.0)
# Upper bound when the caller asked for no timeout at all.
_NO_TIMEOUT_BUDGET = 60.0


def _attempt_timeout(i):
    return _ATTEMPT_TIMEOUTS[min(i, len(_ATTEMPT_TIMEOUTS) - 1)]


def connect(address, timeout, source_address=None, socket_options=None, family=0, default_timeout=None):
    """Like socket.create_connection(), but a connect that stalls is retried on a new socket.

    [timeout] is the caller's: the overall connect budget, and what the returned socket is set to.
    [default_timeout] is the sentinel the caller uses for "use the global default"."""
    host, port = address
    if host.startswith("[") and host.endswith("]"):
        host = host[1:-1]
    infos = socket.getaddrinfo(host, port, family, socket.SOCK_STREAM)
    if source_address is not None:
        af = socket.AF_INET6 if ":" in source_address[0] else socket.AF_INET
        infos = [info for info in infos if info[0] == af]
    if not infos:
        raise OSError("getaddrinfo returns an empty list")

    use_default = timeout is default_timeout or timeout is socket._GLOBAL_DEFAULT_TIMEOUT
    final_timeout = socket.getdefaulttimeout() if use_default else timeout
    budget = final_timeout if isinstance(final_timeout, (int, float)) and final_timeout > 0 else _NO_TIMEOUT_BUDGET
    deadline = time.monotonic() + budget

    last_error = None
    attempt = 0
    while True:
        timed_out = False
        for af, socktype, proto, _, sockaddr in infos:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise last_error or TimeoutError("timed out")
            sock = socket.socket(af, socktype, proto)
            try:
                if socket_options:
                    for opt in socket_options:
                        sock.setsockopt(*opt)
                sock.settimeout(min(_attempt_timeout(attempt), remaining))
                if source_address:
                    sock.bind(source_address)
                sock.connect(sockaddr)
                sock.settimeout(final_timeout)
                return sock
            except TimeoutError as e:
                timed_out = True
                last_error = e
                sock.close()
            except OSError as e:
                last_error = e
                sock.close()
            attempt += 1
        if not timed_out:
            # Refused/unreachable everywhere: a new socket won't change that.
            raise last_error
        # IPv4 first from here on: a family that timed out is the likelier one to be broken (IPv6
        # was entirely dead on the network this was measured on).
        infos.sort(key=lambda info: info[0] != socket.AF_INET)


def _patch_stdlib():
    original = socket.create_connection

    def create_connection(address, timeout=socket._GLOBAL_DEFAULT_TIMEOUT, source_address=None, *, all_errors=False):
        return connect(address, timeout, source_address)

    create_connection.__wrapped__ = original
    socket.create_connection = create_connection


def _patch_urllib3():
    try:
        from urllib3.util import connection as u3
    except Exception:  # noqa: BLE001
        return

    def create_connection(address, timeout=u3._DEFAULT_TIMEOUT, source_address=None, socket_options=None):
        return connect(address, timeout, source_address, socket_options,
                       family=u3.allowed_gai_family(), default_timeout=u3._DEFAULT_TIMEOUT)

    u3.create_connection = create_connection


def _patch_yt_dlp():
    import sys
    helper = sys.modules.get("yt_dlp.networking._helper")
    if helper is None:
        return
    original = helper.create_connection

    def create_connection(address, timeout=socket._GLOBAL_DEFAULT_TIMEOUT, source_address=None, *, _create_socket_func=None):
        if _create_socket_func is not None:
            # A proxy's own socket factory; leave that path exactly as it was.
            return original(address, timeout, source_address, _create_socket_func=_create_socket_func)
        return connect(address, timeout, source_address)

    # Each handler imported the function by name, so each module's own reference is replaced.
    for name in ("yt_dlp.networking._helper", "yt_dlp.networking._requests",
                 "yt_dlp.networking._urllib", "yt_dlp.networking._websockets"):
        module = sys.modules.get(name)
        if module is not None and getattr(module, "create_connection", None) is original:
            module.create_connection = create_connection


_installed = False


def install():
    global _installed
    if _installed:
        return
    _installed = True
    for patch in (_patch_stdlib, _patch_urllib3, _patch_yt_dlp):
        try:
            patch()
        except Exception:  # noqa: BLE001
            pass  # a library changed shape: that one keeps its stock behaviour
