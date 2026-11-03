#!/usr/bin/env python3
"""16 KB page-size compliance gate for PocketNotebook APKs.

Android 15+ supports devices with 16 KB kernel page sizes (mandatory for new
Play submissions targeting SDK 35+ since Nov 2025; real 16 KB phones like the
Redmi Turbo 4 Pro / Pixel 8+ line reject 4 KB-aligned libs outright). For every
uncompressed shared library inside the APK both must hold:

  1. every PT_LOAD segment of the ELF is aligned to >= 16384 bytes
  2. the ZIP entry's data offset is aligned to 16384 bytes
     (only for STORED entries — compressed ones are extracted at install)

Usage:  python3 scripts/check_16k_alignment.py <apk>...
Exit 1 with a clear message on the first violation. Pure stdlib, no deps.
"""
import struct
import sys
import zipfile

PAGE = 16384
PT_LOAD = 1


def elf_load_alignments(blob: bytes):
    """Return [p_align] for every PT_LOAD in a 64-bit ELF."""
    if len(blob) < 64 or blob[:4] != b"\x7fELF":
        return []
    if blob[4] != 2:  # ELFCLASS64
        return []
    e_phoff = struct.unpack_from("<Q", blob, 32)[0]
    e_phentsize, e_phnum = struct.unpack_from("<HH", blob, 54)
    out = []
    for i in range(e_phnum):
        base = e_phoff + i * e_phentsize
        if base + 56 > len(blob):
            break
        p_type = struct.unpack_from("<I", blob, base)[0]
        if p_type == PT_LOAD:
            out.append(struct.unpack_from("<Q", blob, base + 48)[0])
    return out


def check_apk(apk_path: str) -> bool:
    ok = True
    checked = 0
    with zipfile.ZipFile(apk_path) as z:
        for info in z.infolist():
            name = info.filename
            if not name.endswith(".so"):
                continue
            checked += 1

            # 1) ELF PT_LOAD alignment
            with z.open(info) as f:
                blob = f.read()
            aligns = elf_load_alignments(blob)
            bad = [a for a in aligns if a < PAGE]
            if bad:
                ok = False
                print(
                    f"16KB FAIL: {name}: PT_LOAD alignment(s) "
                    f"{', '.join(hex(a) for a in aligns)} < {PAGE} "
                    f"(rebuild with -Wl,-z,max-page-size=16384)"
                )

            # 2) ZIP stored-entry data offset alignment
            if info.compress_type == 0:  # STORED (uncompressed, mmap'd)
                with open(apk_path, "rb") as f:
                    f.seek(info.header_offset)
                    hdr = f.read(30)
                    nlen, elen = struct.unpack_from("<HH", hdr, 26)
                data_off = info.header_offset + 30 + nlen + elen
                if data_off % PAGE:
                    ok = False
                    print(
                        f"16KB FAIL: {name}: ZIP data offset {data_off} not "
                        f"divisible by {PAGE} (AGP must be >= 8.5.1)"
                    )

    print(f"16KB check: {checked} native lib(s) in {apk_path.split('/')[-1]} -> {'OK' if ok else 'FAILED'}")
    return ok


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    results = [check_apk(p) for p in sys.argv[1:]]
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
