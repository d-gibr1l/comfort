"""Shrinks the bundled native runtimes (jniLibs/<abi>/libpython.zip.so and libaria2c.zip.so) without
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
import fnmatch
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "jniLibs")
ZIPS = ("libpython.zip.so", "libaria2c.zip.so")

DROP = [
    # CPython's test and example extension modules.
    "usr/lib/python3.*/lib-dynload/_test*",
    "usr/lib/python3.*/lib-dynload/_ctypes_test*",
    "usr/lib/python3.*/lib-dynload/_xxtestfuzz*",
    "usr/lib/python3.*/lib-dynload/xx*",
    "usr/lib/python3.*/__phello__/*",
    # pycryptodome's own test suite (Cryptodome.SelfTest; nothing imports it).
    "usr/lib/python3.*/site-packages/Cryptodome/SelfTest/*",
    # Type stubs: only read by type checkers.
    "*.pyi",
    # Tcl/Tk and Tcl's database drivers (no tkinter here).
    "usr/lib/tcl8/*", "usr/lib/tdbc*", "usr/lib/thread2.*", "usr/lib/pkgIndex.tcl",
    "usr/lib/libtk.so", "usr/lib/libtclstub*", "usr/lib/libtkstub*",
    # Build-time files.
    "usr/lib/*Config.sh", "usr/lib/*.la", "usr/lib/*.a", "usr/lib/cmake/*", "usr/lib/pkgconfig/*",
    # A Java trust store, irrelevant to Python.
    "usr/lib/jvm/*",
]


def dropped(name):
    path = name.rstrip("/")
    return any(fnmatch.fnmatchcase(path, p) or fnmatch.fnmatchcase(name, p) or
               (p.endswith("/*") and path == p[:-2]) for p in DROP)


def is_symlink(info):
    return (info.external_attr >> 16) & 0o170000 == 0o120000


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


def trim(path, dry_run):
    before = os.path.getsize(path)
    removed, stripped, saved_raw = [], [], 0
    tmpdir = tempfile.mkdtemp()
    out_path = path + ".tmp"
    try:
        with zipfile.ZipFile(path) as zin, zipfile.ZipFile(out_path, "w") as zout:
            names = {i.filename for i in zin.infolist()}
            for info in zin.infolist():
                data = zin.read(info)
                if dropped(info.filename):
                    removed.append(info.filename)
                    saved_raw += info.file_size
                    continue
                if is_symlink(info):
                    target = os.path.normpath(os.path.join(os.path.dirname(info.filename), data.decode()))
                    if target in names and dropped(target):
                        removed.append(info.filename)
                        continue
                elif not info.is_dir():
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


if __name__ == "__main__":
    main()
