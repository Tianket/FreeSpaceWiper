"""Prints how many 4 KiB blocks of a raw disk image still contain the marker."""
import sys

MARKER = b"SECRET-MARKER-"
BLOCK = 4096
blocks = 0
with open(sys.argv[1], "rb") as f:
    while True:
        chunk = f.read(BLOCK * 1024)
        if not chunk:
            break
        if MARKER not in chunk:
            continue
        for off in range(0, len(chunk), BLOCK):
            if MARKER in chunk[off:off + BLOCK]:
                blocks += 1
print(blocks)
