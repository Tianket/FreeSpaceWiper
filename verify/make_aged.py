"""Fills a volume to the given percentage with small files, alternating a "live" file of random
data and a "secret" file whose blocks carry a marker, then deletes the secret files. What is left
is a volume in everyday condition: mostly full, with the free space scattered between files."""
import os
import sys

root, percent = sys.argv[1], int(sys.argv[2])
BLOCK = 4096
MB = 1 << 20
live = os.path.join(root, "live")
os.makedirs(live, exist_ok=True)


def used_percent():
    st = os.statvfs(root)
    return 100 - st.f_bfree * 100 // st.f_blocks


def write(path, data):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
    os.write(fd, data)
    os.fsync(fd)
    os.close(fd)


secrets = []
serial = 0
n = 0
while used_percent() < percent:
    size_mb = 1 + n % 5                      # 1 to 5 MiB, so the gaps differ in size
    write(os.path.join(live, "keep-%05d.bin" % n), os.urandom(size_mb * MB))
    blocks = []
    for _ in range(size_mb * MB // BLOCK):
        head = b"SECRET-MARKER-%010d-" % serial
        blocks.append((head * (BLOCK // len(head) + 1))[:BLOCK])
        serial += 1
    path = os.path.join(live, "secret-%05d.bin" % n)
    write(path, b"".join(blocks))
    secrets.append(path)
    n += 1
for path in secrets:
    os.remove(path)
os.sync()
print("aged layout: %d live files stay, %d secret files (%d blocks) deleted" % (n, n, serial))
