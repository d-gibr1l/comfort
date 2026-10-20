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
connects hanging 20-62s), 73s and 4.8s with this.

curl_cffi (yt-dlp's path when impersonation is on) gets the same treatment with longer attempts
(4s, 6s, 8s): libcurl already splits each attempt across a host's addresses, and 3s attempts made
a request fail in a first try. Measured with requests alternating stock/fixed so both saw the
same network, 25 each, twice: 87.1s vs 57.9s total (worst 13.7s vs 8.2s), then 17.8s vs 9.7s
(worst 5.4s vs 0.9s), no failures either way. For a download (stream=True) its stall limit is
also brought back to yt-dlp's own timeout: curl_cffi turns yt-dlp's (timeout, timeout) into a
40s no-data limit (connect + read), so a connection that died mid-file sat 40s before a retry.
"""
import os
import socket
import time

# Per-attempt connect timeouts in seconds; the last one repeats. A good connect took <1s on the
# bad network and ~2.5s at worst, so a 3s attempt only gives up on a connection that isn't coming.
_ATTEMPT_TIMEOUTS = (3.0, 4.0, 6.0)
# curl_cffi's attempts: longer, since libcurl divides each one between a host's addresses.
_CURL_ATTEMPT_TIMEOUTS = (4.0, 6.0, 8.0)
# Upper bound when the caller asked for no timeout at all.
_NO_TIMEOUT_BUDGET = 60.0


def _attempt_timeout(i):
    return _ATTEMPT_TIMEOUTS[min(i, len(_ATTEMPT_TIMEOUTS) - 1)]


# Set once an IPv6 connect timed out and an IPv4 one then worked: IPv6 is dead on this network, so
# every later connect in this process goes to IPv4 first instead of losing an attempt to IPv6.
_ipv4_first = False


def _interleave_families(infos):
    """IPv6 and IPv4 addresses alternated (each family in its own order), as Happy Eyeballs
    (RFC 8305) does. getaddrinfo puts all of a host's IPv6 addresses first, and with IPv6 dead (a
    phone on such a Wi-Fi, 2026-10-15) open.spotify.com's four IPv6 addresses used up a 15s connect
    budget (3+4+6+2s) before one IPv4 address was tried — every Spotify lookup failed, while curl,
    which races both, connected over IPv4 in 0.1s. Alternated, the IPv4 one is second, ~3s in."""
    v6 = [i for i in infos if i[0] == socket.AF_INET6]
    others = [i for i in infos if i[0] != socket.AF_INET6]
    if not v6 or not others:
        return infos
    first, second = (v6, others) if infos[0][0] == socket.AF_INET6 else (others, v6)
    out = []
    for i in range(max(len(first), len(second))):
        if i < len(first):
            out.append(first[i])
        if i < len(second):
            out.append(second[i])
    return out


def connect(address, timeout, source_address=None, socket_options=None, family=0, default_timeout=None):
    """Like socket.create_connection(), but a connect that stalls is retried on a new socket.

    [timeout] is the caller's: the overall connect budget, and what the returned socket is set to.
    [default_timeout] is the sentinel the caller uses for "use the global default"."""
    global _ipv4_first
    host, port = address
    if host.startswith("[") and host.endswith("]"):
        host = host[1:-1]
    infos = socket.getaddrinfo(host, port, family, socket.SOCK_STREAM)
    if source_address is not None:
        af = socket.AF_INET6 if ":" in source_address[0] else socket.AF_INET
        infos = [info for info in infos if info[0] == af]
    if not infos:
        raise OSError("getaddrinfo returns an empty list")
    infos = _interleave_families(infos)
    if _ipv4_first:
        infos.sort(key=lambda info: info[0] != socket.AF_INET)

    use_default = timeout is default_timeout or timeout is socket._GLOBAL_DEFAULT_TIMEOUT
    final_timeout = socket.getdefaulttimeout() if use_default else timeout
    budget = final_timeout if isinstance(final_timeout, (int, float)) and final_timeout > 0 else _NO_TIMEOUT_BUDGET
    deadline = time.monotonic() + budget

    last_error = None
    attempt = 0
    v6_timed_out = False
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
                if v6_timed_out and af == socket.AF_INET:
                    _ipv4_first = True
                return sock
            except TimeoutError as e:
                timed_out = True
                if af == socket.AF_INET6:
                    v6_timed_out = True
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


def _patch_curl_cffi():
    import sys
    if "curl_cffi.requests" not in sys.modules:
        return  # not loaded in this job (only yt-dlp's impersonation uses it)
    from curl_cffi.requests import Session

    original = Session.request

    def request(self, *args, **kwargs):
        timeout = kwargs.get("timeout")
        # yt-dlp always passes (connect, read); anything else is left exactly as it was.
        if not (isinstance(timeout, tuple) and len(timeout) == 2 and timeout[0] and timeout[1]):
            return original(self, *args, **kwargs)
        connect_budget, read = timeout
        # curl_cffi's no-data limit is connect + read. For a download keep it at yt-dlp's read
        # timeout; for other requests (a total-time limit there) keep curl_cffi's own sum.
        stall_limit = read if kwargs.get("stream") else connect_budget + read
        deadline = time.monotonic() + connect_budget
        attempt = 0
        while True:
            remaining = deadline - time.monotonic()
            attempt_timeout = min(_CURL_ATTEMPT_TIMEOUTS[min(attempt, len(_CURL_ATTEMPT_TIMEOUTS) - 1)], max(remaining, 0.5))
            kwargs["timeout"] = (attempt_timeout, max(stall_limit - attempt_timeout, 1.0))
            try:
                return original(self, *args, **kwargs)
            except Exception as e:  # noqa: BLE001
                # Only a connect that got no answer; "refused"/"unreachable" won't change on retry.
                stalled_connect = getattr(e, "code", None) == 28 and "Connection timed out" in str(e)
                if not stalled_connect or time.monotonic() >= deadline:
                    raise
            attempt += 1

    Session.request = request


_installed = False


def install():
    global _installed
    if _installed:
        return
    _installed = True
    for patch in (_patch_stdlib, _patch_urllib3, _patch_yt_dlp, _patch_curl_cffi):
        try:
            patch()
        except Exception:  # noqa: BLE001
            pass  # a library changed shape: that one keeps its stock behaviour


# --- SOCKS proxies for urllib -------------------------------------------------------------------
# requests (gallery-dl, Instaloader) does SOCKS through PySocks on its own; urllib (the size checks
# below, Spotify's lookups, lyrics) doesn't — its ProxyHandler only speaks HTTP, so a socks:// proxy
# made it tunnel HTTP to the SOCKS port and fail. build_opener()/urlopen() here are urllib's own,
# except through PySocks when the proxy is a SOCKS one. Not PySocks's own sockshandler: it calls
# ssl.match_hostname, gone since Python 3.12.

_SOCKS_TYPES = {"socks4": (1, False), "socks4a": (1, True), "socks5": (2, False), "socks5h": (2, True)}


def _env_proxy():
    for name in ("https_proxy", "HTTPS_PROXY", "all_proxy", "ALL_PROXY", "http_proxy", "HTTP_PROXY"):
        value = os.environ.get(name)
        if value:
            return value
    return None


def _socks_args(proxy_url):
    """PySocks' connection arguments for a socks:// proxy URL, None for any other kind. Raises when
    it is one and PySocks is missing: failing beats a request that skips the proxy."""
    import urllib.parse
    parts = urllib.parse.urlsplit(proxy_url or "")
    kind = _SOCKS_TYPES.get((parts.scheme or "").lower())
    if kind is None:
        return None
    import socks  # noqa: F401 — PySocks, bundled (see PythonRuntime)
    proxy_type, rdns = kind
    user = urllib.parse.unquote(parts.username) if parts.username else None
    password = urllib.parse.unquote(parts.password) if parts.password else None
    return (proxy_type, parts.hostname, parts.port or 1080, rdns, user, password)


def _socks_connection_classes():
    import http.client
    import socks

    def connect(conn):
        proxy_type, host, port, rdns, user, password = conn.socks_args
        return socks.create_connection((conn.host, conn.port), conn.timeout, None,
                                       proxy_type, host, port, rdns, user, password)

    class SocksHTTPConnection(http.client.HTTPConnection):
        def __init__(self, *args, socks_args, **kwargs):
            super().__init__(*args, **kwargs)
            self.socks_args = socks_args

        def connect(self):
            self.sock = connect(self)

    class SocksHTTPSConnection(http.client.HTTPSConnection):
        def __init__(self, *args, socks_args, **kwargs):
            super().__init__(*args, **kwargs)
            self.socks_args = socks_args

        def connect(self):
            self.sock = self._context.wrap_socket(connect(self), server_hostname=self.host)

    return SocksHTTPConnection, SocksHTTPSConnection


def build_opener(*handlers, proxy_url=None):
    """urllib.request.build_opener(*handlers) through [proxy_url], or the job's own proxy (the
    environment PythonRuntime sets) without one — a SOCKS one included."""
    import functools
    import urllib.request
    proxy = proxy_url or _env_proxy()
    socks_args = _socks_args(proxy) if proxy else None
    if socks_args is None:
        if proxy_url:
            handlers += (urllib.request.ProxyHandler({"http": proxy_url, "https": proxy_url}),)
        return urllib.request.build_opener(*handlers)
    http_class, https_class = _socks_connection_classes()
    context = next((h._context for h in handlers if isinstance(h, urllib.request.HTTPSHandler)), None)

    class SocksHandler(urllib.request.HTTPHandler, urllib.request.HTTPSHandler):
        def __init__(self):
            urllib.request.HTTPHandler.__init__(self)
            urllib.request.HTTPSHandler.__init__(self, context=context)

        def http_open(self, req):
            return self.do_open(functools.partial(http_class, socks_args=socks_args), req)

        def https_open(self, req):
            return self.do_open(functools.partial(https_class, socks_args=socks_args), req, context=self._context)

    rest = [h for h in handlers if not isinstance(h, (urllib.request.HTTPHandler, urllib.request.HTTPSHandler, urllib.request.ProxyHandler))]
    # ProxyHandler({}): the environment's socks:// proxy must not also be tried as an HTTP one.
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), SocksHandler(), *rest)


def urlopen(req, timeout=None, context=None):
    """urllib.request.urlopen(req, timeout=..., context=...), through the job's proxy whatever kind
    it is."""
    import urllib.request
    handlers = (urllib.request.HTTPSHandler(context=context),) if context is not None else ()
    return build_opener(*handlers).open(req, timeout=timeout)


# --- Media sizes -------------------------------------------------------------------------------
# For listings whose engine doesn't report sizes (gallery-dl's --dump-json, Instaloader): asks the
# media server itself, so the preview can show how big a download will be before it starts.

_SIZE_UA = ("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/128.0 Mobile Safari/537.36")


_ssl_context = None


def _size_context():
    # Built once: loading the CA bundle took ~1s per request when made each time.
    global _ssl_context
    if _ssl_context is None:
        import ssl
        try:
            import certifi
            _ssl_context = ssl.create_default_context(cafile=certifi.where())
        except Exception:  # noqa: BLE001
            _ssl_context = ssl.create_default_context()
    return _ssl_context


def _size_of(url, timeout, headers):
    import urllib.error
    import urllib.request
    context = _size_context()
    base = {"User-Agent": _SIZE_UA, **(headers or {})}
    # HEAD first; some servers refuse it or leave out Content-Length, so then a one-byte ranged GET,
    # whose Content-Range ("bytes 0-0/12345") carries the full size.
    attempts = (("HEAD", {}), ("GET", {"Range": "bytes=0-0"}))
    for method, extra in attempts:
        try:
            req = urllib.request.Request(url, method=method, headers={**base, **extra})
            with urlopen(req, timeout=timeout, context=context) as resp:
                total = (resp.headers.get("Content-Range") or "").rpartition("/")[2]
                if total.isdigit():
                    return int(total)
                length = resp.headers.get("Content-Length")
                if method == "HEAD" and length and length.isdigit() and int(length) > 0:
                    return int(length)
        except urllib.error.HTTPError as e:
            if e.code in (404, 410):
                return None  # gone: a second request won't find it either
        except Exception:  # noqa: BLE001 — no size is fine, the listing still works without one
            continue
    return None


def content_lengths(urls, timeout=4.0, deadline=6.0, workers=8, headers=None):
    """{url: size in bytes} for whichever of [urls] answered within [deadline] seconds overall —
    a slow or missing size never holds up the listing it decorates. Only http(s) links are asked."""
    import concurrent.futures
    wanted = list(dict.fromkeys(u for u in urls if isinstance(u, str) and u.startswith(("http://", "https://"))))
    if not wanted:
        return {}
    sizes = {}
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=min(workers, len(wanted)))
    futures = {pool.submit(_size_of, u, timeout, headers): u for u in wanted}
    try:
        for future in concurrent.futures.as_completed(futures, timeout=deadline):
            size = future.result()
            if size:
                sizes[futures[future]] = size
    except concurrent.futures.TimeoutError:
        pass
    finally:
        pool.shutdown(wait=False, cancel_futures=True)
    return sizes
