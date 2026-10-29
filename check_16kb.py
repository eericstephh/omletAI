"""Checks 16 KB page-size alignment of every arm64 .so inside an APK.

Usage: python check_16kb.py <path-to-apk>

Android 15+ requires every PT_LOAD segment of every shipped .so to carry
p_align >= 16384 (max-page-size). Reads the ELF program headers directly
from the zip entries — no extraction, no external tools.
"""
import struct
import sys
import zipfile

PT_LOAD = 1


def load_alignment(data: bytes):
    """Returns the minimum PT_LOAD p_align of a 64-bit ELF, or None."""
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return None
    ei_class = data[4]
    if ei_class != 2:  # 64-bit only — this app ships arm64-v8a only
        return -1
    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
    e_phnum = struct.unpack_from("<H", data, 0x38)[0]
    min_align = None
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", data, off)[0]
        if p_type != PT_LOAD:
            continue
        p_align = struct.unpack_from("<Q", data, off + 0x30)[0]
        if min_align is None or p_align < min_align:
            min_align = p_align
    return min_align


def main(apk_path: str):
    ok, bad = [], []
    with zipfile.ZipFile(apk_path) as z:
        for info in z.infolist():
            if not info.filename.startswith("lib/arm64-v8a/") or not info.filename.endswith(".so"):
                continue
            align = load_alignment(z.read(info))
            name = info.filename.rsplit("/", 1)[-1]
            if align is None:
                bad.append((name, "not an ELF"))
            elif align == -1:
                bad.append((name, "32-bit ELF"))
            elif align >= 16384:
                ok.append((name, align))
            else:
                bad.append((name, f"p_align={align} (<16384)"))
    print(f"== {apk_path} ==")
    print(f"ALIGNED ({len(ok)}):")
    for name, align in sorted(ok):
        print(f"  OK   {name}  (p_align={align})")
    print(f"NOT ALIGNED ({len(bad)}):")
    for name, why in sorted(bad):
        print(f"  FAIL {name}  ({why})")
    return len(bad)


if __name__ == "__main__":
    sys.exit(1 if main(sys.argv[1]) else 0)
