"""Shrinks the native runtimes (jniLibs/<abi>/libpython.zip.so, and aria2c's libraries in
release-assets/aria2-libs-<abi>.zip, downloaded when aria2 is turned on) without
changing what runs: strips debug info from shared libraries that still carry it, and drops files the
app never loads — CPython's own test modules, Tcl/Tk and its database drivers, build-time files
(CMake, pkg-config, libtool, static libraries, type stubs), a Java certificate store, and
pycryptodome's self-test suite.

Every other entry is copied as it is, attributes included: the zips store symlinks
(`libz.so -> libz.so.1.3.2`), which PythonRuntime.unzipStreamTo repairs from the entry's
contents, so their bytes and modes must not change.

Run it on fresh zips from ytdlnis-packages after replacing them, then bump
PythonRuntime.RUNTIME_VERSION so installed copies are unpacked again:

    python tools/trim_runtime_zips.py            # rewrite in place
    python tools/trim_runtime_zips.py --dry-run  # only report

Needs llvm-strip (any target architecture) on PATH. Running it twice changes nothing.
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "jniLibs")
ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "release-assets")
ZIPS = ("libpython.zip.so",)

# Matched against each entry's path: "*" stays within one path segment, a trailing "/**" is that
# directory and everything in it, and a pattern with no "/" matches a file name at any depth.
DROP = [
    # CPython's test and example extension modules.
    "usr/lib/python3.*/lib-dynload/_test*",
    "usr/lib/python3.*/lib-dynload/_ctypes_test*",
    "usr/lib/python3.*/lib-dynload/_xxtestfuzz*",
    "usr/lib/python3.*/lib-dynload/xx*",
    "usr/lib/python3.*/__phello__/**",
    # pycryptodome's own test suite (Cryptodome.SelfTest; nothing imports it).
    "usr/lib/python3.*/site-packages/Cryptodome/SelfTest/**",
    # Type stubs: only read by type checkers.
    "*.pyi",
    # Tcl/Tk and Tcl's database drivers (no tkinter here).
    "usr/lib/tcl8/**", "usr/lib/tdbc*/**", "usr/lib/thread2.*/**", "usr/lib/pkgIndex.tcl",
    "usr/lib/libtk.so", "usr/lib/libtclstub*", "usr/lib/libtkstub*",
    # Build-time files, at usr/lib's top level.
    "usr/lib/*Config.sh", "usr/lib/*.la", "usr/lib/*.a", "usr/lib/cmake/**", "usr/lib/pkgconfig/**",
    # A Java trust store, irrelevant to Python.
    "usr/lib/jvm/**",
]

# Only in one of the two zips. Each was checked to be loaded by nothing: no library in the zip (nor
# its launcher, libpython.so / libaria2c.so) lists it as needed, and for Python modules, nothing
# the engines or the app's wrappers import reaches it except another dropped module or a module's
# own `if __name__ == "__main__"` self-test (heapq's doctest, which brings in unittest and pdb).
DROP_IN = {
    "libpython.zip.so": [
        # C++ runtime (curl_cffi brings its own, curl_cffi.libs/libc++_shared-*.so) and libpng
        # (only Tk used it): nothing in the runtime links them.
        "usr/lib/libc++_shared.so*", "usr/lib/libpng16.so*",
        # Debugger, docs browser, the interactive shell, the test framework, and what only they use.
        "usr/lib/python3.*/unittest/**", "usr/lib/python3.*/_pyrepl/**",
        "usr/lib/python3.*/pdb.py", "usr/lib/python3.*/bdb.py", "usr/lib/python3.*/pydoc.py",
        "usr/lib/python3.*/doctest.py",
        # Imported by nothing at all.
        "usr/lib/python3.*/venv/**", "usr/lib/python3.*/wsgiref/**", "usr/lib/python3.*/curses/**",
        "usr/lib/python3.*/dbm/**", "usr/lib/python3.*/_pyio.py", "usr/lib/python3.*/smtplib.py",
        "usr/lib/python3.*/imaplib.py", "usr/lib/python3.*/poplib.py", "usr/lib/python3.*/mailbox.py",
        "usr/lib/python3.*/profile.py", "usr/lib/python3.*/cProfile.py", "usr/lib/python3.*/timeit.py",
        "usr/lib/python3.*/tabnanny.py", "usr/lib/python3.*/trace.py",
        "usr/lib/python3.*/site-packages/xcbgen/**",
        # Building C extensions: Makefile, config.c, python.o and the rest.
        "usr/lib/python3.*/config-3.*/**",
        # pycryptodome beyond what yt-dlp (AES, Blowfish, PKCS1 OAEP/v1.5, CMAC, SHA1, RSA) and
        # gallery-dl (AES) import: elliptic curves (ECC and its 0.6 MB _ec_ws library), DSA,
        # ElGamal, DH/HPKE, signatures, and ciphers/hashes none of it uses.
        "usr/lib/python3.*/site-packages/Cryptodome/PublicKey/ECC.py",
        "usr/lib/python3.*/site-packages/Cryptodome/PublicKey/DSA.py",
        "usr/lib/python3.*/site-packages/Cryptodome/PublicKey/ElGamal.py",
        "usr/lib/python3.*/site-packages/Cryptodome/PublicKey/_curve.py",
        "usr/lib/python3.*/site-packages/Cryptodome/PublicKey/_edwards.py",
        "usr/lib/python3.*/site-packages/Cryptodome/PublicKey/_montgomery.py",
        "usr/lib/python3.*/site-packages/Cryptodome/PublicKey/_nist_ecc.py",
        "usr/lib/python3.*/site-packages/Cryptodome/PublicKey/_point.py",
        "usr/lib/python3.*/site-packages/Cryptodome/PublicKey/_ec_ws*",
        "usr/lib/python3.*/site-packages/Cryptodome/PublicKey/_curve25519*",
        "usr/lib/python3.*/site-packages/Cryptodome/PublicKey/_curve448*",
        "usr/lib/python3.*/site-packages/Cryptodome/PublicKey/_ed25519*",
        "usr/lib/python3.*/site-packages/Cryptodome/PublicKey/_ed448*",
        # Signature/pss.py stays: Cipher.PKCS1_OAEP imports its MGF1.
        "usr/lib/python3.*/site-packages/Cryptodome/Signature/DSS.py",
        "usr/lib/python3.*/site-packages/Cryptodome/Signature/PKCS1_*.py",
        "usr/lib/python3.*/site-packages/Cryptodome/Signature/eddsa.py",
        "usr/lib/python3.*/site-packages/Cryptodome/Signature/pkcs1_15.py",
        "usr/lib/python3.*/site-packages/Cryptodome/Protocol/DH.py",
        "usr/lib/python3.*/site-packages/Cryptodome/Protocol/HPKE.py",
        "usr/lib/python3.*/site-packages/Cryptodome/Protocol/SecretSharing.py",
        "usr/lib/python3.*/site-packages/Cryptodome/Cipher/ARC4.py",
        "usr/lib/python3.*/site-packages/Cryptodome/Cipher/_ARC4*",
        "usr/lib/python3.*/site-packages/Cryptodome/Cipher/CAST.py",
        "usr/lib/python3.*/site-packages/Cryptodome/Cipher/_raw_cast*",
        "usr/lib/python3.*/site-packages/Cryptodome/Cipher/ChaCha20*",
        "usr/lib/python3.*/site-packages/Cryptodome/Cipher/_chacha20*",
        "usr/lib/python3.*/site-packages/Cryptodome/Hash/BLAKE2b.py",
        "usr/lib/python3.*/site-packages/Cryptodome/Hash/_BLAKE2b*",
        "usr/lib/python3.*/site-packages/Cryptodome/Hash/KMAC*", "usr/lib/python3.*/site-packages/Cryptodome/Hash/KangarooTwelve.py",
        "usr/lib/python3.*/site-packages/Cryptodome/Hash/MD2.py", "usr/lib/python3.*/site-packages/Cryptodome/Hash/_MD2*",
        "usr/lib/python3.*/site-packages/Cryptodome/Hash/MD4.py", "usr/lib/python3.*/site-packages/Cryptodome/Hash/_MD4*",
        "usr/lib/python3.*/site-packages/Cryptodome/Hash/Poly1305.py", "usr/lib/python3.*/site-packages/Cryptodome/Hash/_poly1305*",
        "usr/lib/python3.*/site-packages/Cryptodome/Hash/RIPEMD*", "usr/lib/python3.*/site-packages/Cryptodome/Hash/_RIPEMD160*",
        "usr/lib/python3.*/site-packages/Cryptodome/Hash/SHA.py",
        "usr/lib/python3.*/site-packages/Cryptodome/Hash/SHAKE*", "usr/lib/python3.*/site-packages/Cryptodome/Hash/cSHAKE*",
        "usr/lib/python3.*/site-packages/Cryptodome/Hash/TupleHash*", "usr/lib/python3.*/site-packages/Cryptodome/Hash/TurboSHAKE*",
        "usr/lib/python3.*/site-packages/Cryptodome/Util/Counter.py", "usr/lib/python3.*/site-packages/Cryptodome/Util/RFC1751.py",
    ],
    "libaria2c.zip.so": [
        # Not linked by aria2c or anything it loads: C++ bindings for GnuTLS and GMP, GnuTLS's
        # DANE add-on, and liblzma (libxml2 here was built without it).
        "usr/lib/libgnutlsxx.so*", "usr/lib/libgmpxx.so*", "usr/lib/libgnutls-dane.so*", "usr/lib/liblzma.so*",
    ],
}


def _compile(pattern):
    if "/" not in pattern:
        return re.compile(r"(?:.*/)?" + _segments(pattern) + r"/?")
    if pattern.endswith("/**"):
        return re.compile(_segments(pattern[:-3]) + r"(?:/.*)?")
    return re.compile(_segments(pattern) + r"/?")


def _segments(pattern):
    """[pattern] as a regex where "*" and "?" don't cross a "/"."""
    return "".join("[^/]*" if c == "*" else "[^/]" if c == "?" else re.escape(c) for c in pattern)


_DROP_RX = [_compile(p) for p in DROP]
_DROP_IN_RX = {z: [_compile(p) for p in ps] for z, ps in DROP_IN.items()}


def dropped(name, zip_name=None):
    return any(rx.fullmatch(name) for rx in _DROP_RX + _DROP_IN_RX.get(zip_name, []))


def is_symlink(info):
    return (info.external_attr >> 16) & 0o170000 == 0o120000


def is_vendored_lib(name):
    """A library a wheel bundles in its "<package>.libs/" folder (auditwheel). patchelf already
    rewrote those, and llvm-strip on top broke them: curl_cffi.libs/libc++_shared's version
    needs ended up pointing into the wrong strings ("cannot find "operty_get"" when loaded on the
    phone), so curl_cffi didn't import and every yt-dlp download with impersonation failed."""
    return ".libs/" in name


def strip_if_needed(data, tmpdir):
    """[data] stripped of debug info and the static symbol table, or None if it has neither."""
    # Shared libraries and executables only (e_type 3 / 2), not object files like config's python.o.
    if not data.startswith(b"\x7fELF") or data[16] not in (2, 3):
        return None
    src = os.path.join(tmpdir, "in.so")
    with open(src, "wb") as f:
        f.write(data)
    sections = subprocess.run(["llvm-readelf", "-S", "-W", src], capture_output=True, text=True).stdout
    if ".debug_" not in sections and ".symtab" not in sections:
        return None
    subprocess.run(["llvm-strip", "--strip-unneeded", src], check=True)
    with open(src, "rb") as f:
        out = f.read()
    return out if len(out) < len(data) else None


def trim(path, dry_run, zname=None):
    zname = zname or os.path.basename(path)
    before = os.path.getsize(path)
    removed, stripped, saved_raw = [], [], 0
    tmpdir = tempfile.mkdtemp()
    out_path = path + ".tmp"
    try:
        with zipfile.ZipFile(path) as zin, zipfile.ZipFile(out_path, "w") as zout:
            names = {i.filename for i in zin.infolist()}
            for info in zin.infolist():
                data = zin.read(info)
                if dropped(info.filename, zname):
                    removed.append(info.filename)
                    saved_raw += info.file_size
                    continue
                if is_symlink(info):
                    target = os.path.normpath(os.path.join(os.path.dirname(info.filename), data.decode()))
                    if target in names and dropped(target, zname):
                        removed.append(info.filename)
                        continue
                elif not info.is_dir() and not is_vendored_lib(info.filename):
                    s = strip_if_needed(data, tmpdir)
                    if s is not None:
                        stripped.append(info.filename)
                        saved_raw += len(data) - len(s)
                        data = s
                new = zipfile.ZipInfo(info.filename, info.date_time)
                new.external_attr, new.create_system = info.external_attr, info.create_system
                new.compress_type = info.compress_type
                zout.writestr(new, data, compress_type=info.compress_type, compresslevel=9)
        after = os.path.getsize(out_path)
        rel = os.path.relpath(path, ROOT)
        print(f"{rel}: {before / 1048576:.2f} -> {after / 1048576:.2f} MB "
              f"(unpacked {saved_raw / 1048576:.2f} MB less; {len(removed)} entries dropped, {len(stripped)} stripped)")
        for s in stripped:
            print("  stripped", s)
        if dry_run or not (removed or stripped) or after >= before:
            os.remove(out_path)
        else:
            os.replace(out_path, path)
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)
        if os.path.exists(out_path):
            os.remove(out_path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    if not shutil.which("llvm-strip") or not shutil.which("llvm-readelf"):
        sys.exit("needs llvm-strip and llvm-readelf on PATH")
    for abi in sorted(os.listdir(ROOT)):
        for z in ZIPS:
            p = os.path.join(ROOT, abi, z)
            if os.path.exists(p):
                trim(p, args.dry_run)
    # aria2c's libraries: a release asset, no longer in the APK (Aria2Runtime downloads it). After
    # changing one, publish it again and update Aria2Runtime's checksums and LIBS_TAG.
    for f in sorted(os.listdir(ASSETS)):
        if f.startswith("aria2-libs-") and f.endswith(".zip"):
            trim(os.path.join(ASSETS, f), args.dry_run, zname="libaria2c.zip.so")


if __name__ == "__main__":
    main()
