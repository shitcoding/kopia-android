#!/usr/bin/env bash
# gen_backup_source.sh <source_dir>
#
# Generate the DETERMINISTIC tree the phone backs up in the `backup`-category E2E flows. The same
# bytes every run and machine, so the copy retained on the host is the authoritative original that a
# Go-kopia restore of the phone's snapshot is compared against byte-for-byte.
#
# Deliberately SMALL (well under 2 MiB): a full backup has to finish inside a flow timeout on a
# software-GPU emulator. Size variety is covered by gen_roundtrip_source.sh, which owns the explicit
# 1 MiB content-splitter boundary cases; this tree only needs enough shape to catch the things that
# have historically broken - a unicode name, a name with a space, a deep nest, an empty file, one
# multi-chunk file, and the pair the ignore-rule flow asserts on.
set -euo pipefail

SRC="${1:?usage: gen_backup_source.sh <source_dir>}"
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

w("notes.txt", b"plain content\n")
w("empty.txt", b"")
w("unicode_日本語.txt", "日本語 内容\n".encode("utf-8"))
w("name with spaces.txt", b"spaces in name\n")
w("deep/one/two/three/buried.txt", b"three levels down\n")
w("photos/large.bin", stream(512 * 1024, "large"))   # spans several content chunks

# The ignore-rule flow (E5) excludes `excluded/` and asserts BOTH of these: the excluded file gone
# AND the control file still present, because absence alone can pass on a broken browse.
w("excluded/secret.txt", b"must not be backed up\n")
w("excluded_control.txt", b"must still be backed up\n")
PY

find "$SRC" -type f | wc -l | tr -d ' ' | xargs -I{} echo "[gen_backup_source] {} files in $SRC"
