"""Writes a file in which every 4 KiB block starts with a recognisable marker."""
import sys

path, megabytes = sys.argv[1], int(sys.argv[2])
BLOCK = 4096
with open(path, "wb") as f:
    for i in range(megabytes * 256):
        head = b"SECRET-MARKER-%010d-" % i
        f.write((head * (BLOCK // len(head) + 1))[:BLOCK])
