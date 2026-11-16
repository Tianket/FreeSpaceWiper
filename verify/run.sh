#!/usr/bin/env bash
# Forensic check of the wipe engine on a real file system.
#
#   1. create a disk image with the given file system and mount it
#   2. write "secret" files whose every block carries a marker, then delete them
#   3. count the marker blocks still readable from the raw image (the baseline: what a
#      recovery tool could find)
#   4. run the wipe engine as an ordinary, unprivileged user (like an Android app)
#   5. count the marker blocks again
#
# Usage: verify/run.sh <ext4|f2fs>
# Options (environment variables):
#   SIZE_MB=3072        size of the disk image
#   LAYOUT=fresh|aged   fresh: one large secret file on an empty file system
#                       aged:  the volume is 85 % full of small "live" files interleaved with
#                              secret files; only the secret ones are deleted, so the free space
#                              is fragmented between files that stay
#   INTERRUPT=1         kill the first wipe with SIGKILL after two seconds, let a second one resume
#   MOUNT_EXTRA=opts    extra mount options, e.g. reserve_root=32768 for f2fs
#   SWEEP=false         switch the engine's sweep phase off, to see what it contributes
#   WIPE_USER=name      run the wipe as this user (needed when the script itself runs as root,
#                       because root may use the reserved blocks an app cannot)
# Needs sudo for the loop mount, a JDK and python3.
set -euo pipefail

FS=${1:-ext4}
SIZE_MB=${SIZE_MB:-3072}
LAYOUT=${LAYOUT:-fresh}
INTERRUPT=${INTERRUPT:-}
MOUNT_EXTRA=${MOUNT_EXTRA:-}
SWEEP=${SWEEP:-true}
WIPE_USER=${WIPE_USER:-}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(dirname "$HERE")
WORK=$(mktemp -d)
chmod 755 "$WORK"
IMG="$WORK/disk.img"
MNT="$WORK/mnt"
CLASSES="$WORK/classes"
mkdir -p "$MNT" "$CLASSES"

cleanup() {
    sudo umount "$MNT" 2>/dev/null || true
    rm -rf "$WORK"
}
trap cleanup EXIT

javac -nowarn -d "$CLASSES" $(find "$ROOT/app/src/main/java/app/freespacewiper/engine" -name '*.java') "$HERE"/*.java

truncate -s "${SIZE_MB}M" "$IMG"
# Discard (TRIM) is switched off everywhere: on a loop device it would punch holes into the image
# and make deleted data disappear by itself, which would prove nothing about the wipe.
case "$FS" in
    ext4) mkfs.ext4 -q -F -E nodiscard "$IMG"; MOUNT_OPTS="loop" ;;
    f2fs) mkfs.f2fs -q -t 0 "$IMG"; MOUNT_OPTS="loop,nodiscard" ;;
    *) echo "unknown file system: $FS"; exit 2 ;;
esac
[ -n "$MOUNT_EXTRA" ] && MOUNT_OPTS="$MOUNT_OPTS,$MOUNT_EXTRA"

mount_image() { sudo mount -o "$MOUNT_OPTS" "$IMG" "$MNT"; }
unmount_image() { sync; sudo umount "$MNT"; }
as_wipe_user() {
    if [ -n "$WIPE_USER" ]; then sudo -u "$WIPE_USER" "$@"; else "$@"; fi
}
count_files() { (ls "$1" 2>/dev/null || true) | wc -l; }
wipe() { as_wipe_user java -cp "$CLASSES" WipeCli "$MNT/wipe" RANDOM 4 1 "$1" "$SWEEP"; }

echo "== $FS, ${SIZE_MB} MiB image, layout: $LAYOUT${INTERRUPT:+, interrupted}${MOUNT_EXTRA:+, mount: $MOUNT_EXTRA}"
mount_image
if [ "$LAYOUT" = "aged" ]; then
    sudo python3 "$HERE/make_aged.py" "$MNT" 85
else
    sudo python3 "$HERE/gen_secret.py" "$MNT/secret.dat" $(( SIZE_MB * 70 / 100 ))
    sync
    sudo rm "$MNT/secret.dat"
fi
unmount_image
BEFORE=$(python3 "$HERE/count_markers.py" "$IMG")
echo "secret data deleted; marker blocks still on the raw disk: $BEFORE"

mount_image
sudo mkdir -p "$MNT/wipe"
sudo chown "${WIPE_USER:-$(id -un)}" "$MNT/wipe"
if [ -n "$INTERRUPT" ]; then
    wipe false > "$WORK/first.log" 2>&1 &
    sleep 2
    sudo pkill -9 -f "WipeCli $MNT/wipe" 2>/dev/null || true
    wait 2>/dev/null || true
    echo "killed the first run; fill files left behind: $(count_files "$MNT/wipe")"
    wipe true
else
    wipe false
fi
# The engine removes its work folder when it is empty, so a missing folder means "nothing left".
LEFT=$(count_files "$MNT/wipe")
unmount_image
AFTER=$(python3 "$HERE/count_markers.py" "$IMG")

echo "marker blocks after the wipe: $AFTER of $BEFORE (fill files left: $LEFT)"
if [ "$BEFORE" -lt 1000 ]; then
    echo "FAIL: the baseline is too small, the test proves nothing"; exit 1
fi
if [ "$LEFT" -ne 0 ]; then
    echo "FAIL: fill files were not cleaned up"; exit 1
fi
LIMIT=$(( BEFORE / 1000 ))
if [ "$AFTER" -gt "$LIMIT" ]; then
    echo "FAIL: more than 0.1% of the deleted data is still readable"; exit 1
fi
echo "PASS"
