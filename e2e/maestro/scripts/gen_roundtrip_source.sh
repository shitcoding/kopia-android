#!/usr/bin/env bash
# gen_roundtrip_source.sh <source_dir>
#
# Generate a DETERMINISTIC source tree for the backup/restore round-trip integrity test. The same
# bytes every run/machine (fixed content, no timestamps/randomness), so the generated tree is the
# authoritative "original" that KopiaKt's restore is compared against byte-for-byte.
#
# The matrix exercises fidelity edge cases: empty file, UTF-8 name+content, full 0x00..0xFF byte range,
# all-zeros / all-0xFF, an incompressible binary that crosses the FIXED-1M content-splitter boundary,
# deep nesting, duplicate basenames in different dirs, a hidden dotfile, and special characters in
# names. (Newline-in-name / leading-trailing-space / case-collision names are intentionally omitted —
# fragile and not reliably preserved by Android SAF. Symlinks/permissions/mtimes are out of scope:
# SafRestoreOutput skips symlinks and restores no metadata.)
set -euo pipefail

SRC="${1:?usage: gen_roundtrip_source.sh <source_dir>}"
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 required for deterministic generation" >&2; exit 1; }

rm -rf "$SRC"
mkdir -p "$SRC"

python3 - "$SRC" <<'PY'
import hashlib, os, sys
root = sys.argv[1]

def w(relpath, data: bytes):
    p = os.path.join(root, relpath)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "wb") as f:
        f.write(data)

def stream(n: int, seed: str) -> bytes:
    """Deterministic, incompressible-ish byte stream (sha256 of a counter)."""
    out = bytearray()
    i = 0
    while len(out) < n:
        out += hashlib.sha256(f"{seed}:{i}".encode()).digest()
        i += 1
    return bytes(out[:n])

MIB = 1024 * 1024

# --- fidelity edge cases (all byte-exact) ---
w("empty.txt", b"")
w("small.txt", b"hello round-trip\n")
w("unicode_日本語_αβγ.txt", "日本語 αβγ 内容\n".encode("utf-8"))
w("all_bytes.bin", bytes(range(256)))
w("all_zeros_4k.bin", b"\x00" * 4096)
w("all_ff_4k.bin", b"\xff" * 4096)
# Content-splitter boundary coverage (FIXED-1M => boundary at every 1 MiB):
w("exactly_1mib.bin", stream(MIB, "exact"))
w("one_over_1mib.bin", stream(MIB + 1, "over"))
w("three_mib_plus_17.bin", stream(3 * MIB + 17, "big"))   # forces multiple content chunks
# Structure cases:
w("nested/a/b/c/deep.txt", b"deeply nested\n")
w("dup/dir1/same.txt", b"dir1 copy\n")
w("dup/dir2/same.txt", b"dir2 copy\n")               # duplicate basename, different content
w(".hidden", b"hidden dotfile\n")
w("name with spaces.txt", b"spaces in name\n")
w("weird#@()[]+=.txt", b"special chars in name\n")

# Print a sorted manifest (relpath \t size \t sha256) to stdout for the caller to retain.
import io
entries = []
for dirpath, _dirs, files in os.walk(root):
    for name in files:
        full = os.path.join(dirpath, name)
        rel = os.path.relpath(full, root)
        with open(full, "rb") as f:
            data = f.read()
        entries.append((rel, len(data), hashlib.sha256(data).hexdigest()))
for rel, size, digest in sorted(entries):
    sys.stdout.write(f"{rel}\t{size}\t{digest}\n")
PY
