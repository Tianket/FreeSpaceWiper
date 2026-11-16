package app.freespacewiper.engine;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Overwrites the free space of one volume. Each pass:
 * <ol>
 *   <li>FILLING: worker threads each write 1 GiB files (1 MiB per write, fsync'ed regularly) until
 *       the file system answers ENOSPC. There is no fixed target size: the kernel decides when the
 *       volume is full, so space freed by the system while we run is covered too. With adaptive
 *       threads the engine starts small and doubles the thread count as long as the measured
 *       write speed keeps improving.</li>
 *   <li>SQUEEZING: one thread squeezes out what is left with 64 KiB and then 4 KiB writes.</li>
 *   <li>SWEEPING: a "full" volume still has free blocks an app cannot allocate, because they are
 *       reserved for the system, and they can hold old data. Which blocks stay free is up to the
 *       allocator, and allocators prefer large free extents. So the sweep makes our own free
 *       space less attractive than the reserved rest: it releases a few of the oldest fill files,
 *       writes that space full of small files, deletes every second one (leaving only small
 *       holes) and fills the volume once more. The allocator now takes the reserved rest first
 *       and leaves holes free that already contain our data.</li>
 *   <li>DELETING: all fill files are removed.</li>
 * </ol>
 * If the run is cancelled or fails, fill files are always removed. If the process is killed, the
 * files stay on disk and a later run with {@code resume=true} adopts them.
 *
 * Never uses fallocate or sparse files: every byte is really written, so the file system has to
 * hand out every block it can.
 */
public final class WipeEngine {

    public static final String FILL_PREFIX = "fill-";
    public static final String TAIL_PREFIX = "tail-";
    public static final String SUFFIX = ".bin";

    private static final int SQUEEZE_BIG = 64 * 1024;
    private static final int SQUEEZE_SMALL = 4096;
    private static final int SQUEEZE_ROUNDS = 6;
    private static final long MIN_SYNC_INTERVAL = 16L << 20;
    /** A thread count has to be this much faster than the previous one to be worth more threads. */
    private static final double TUNE_GAIN = 1.08;
    /** Below this much reserved space there is nothing worth sweeping for. */
    private static final long MIN_SWEEP_RESERVE = 1L << 20;

    private static final int ST_OK = 0;
    private static final int ST_FULL = 1;
    private static final int ST_LIMIT = 2;
    private static final int ST_ERROR = 3;
    private static final int ST_CANCEL = 4;
    private static final int ST_RETIRED = 5;

    private final Platform platform;
    private final WipeConfig cfg;
    private final EngineListener listener;

    private volatile boolean cancelled;
    private volatile Phase phase = Phase.PREPARING;
    private volatile int pass;
    private volatile long freeBytes = -1;
    private volatile long volumeBytes = -1;
    private volatile double emaBps;
    private volatile String failure;
    private volatile long runStartNanos;
    private volatile long passStartNanos;
    private volatile long endNanos;
    private volatile long previousPassesBytes;
    private volatile long unreachableBytes = -1;
    private volatile long lastProgressNanos;
    /** Highest progress reported in the current pass; see Snapshot.permilleFloor. */
    private volatile int permilleHigh;

    // Pause: workers block on pauseLock between two writes.
    private final Object pauseLock = new Object();
    private volatile boolean paused;
    private long pausedSinceNanos;
    private long pausedTotalNanos;

    /** Bytes held in fill files in this pass. */
    private final AtomicLong passBytes = new AtomicLong();
    /** Bytes written by this run, never decreases. */
    private final AtomicLong runBytes = new AtomicLong();
    private final AtomicInteger nextIndex = new AtomicInteger();
    private final AtomicInteger filesInPass = new AtomicInteger();
    private final AtomicInteger activeWorkers = new AtomicInteger();
    private final AtomicInteger diskFullHits = new AtomicInteger();
    private final AtomicInteger totalErrors = new AtomicInteger();
    /** Workers with a slot number at or above this value finish their file and stop. */
    private final AtomicInteger targetWorkers = new AtomicInteger();
    private volatile String firstPassError;
    private volatile boolean tuning;
    private volatile boolean outOfMemory;

    // Only touched by the coordinator thread.
    private long lastSampleNanos;
    private long lastSampleBytes;
    private long lastSeenBytes;
    private boolean stallReported;
    private long tuneWindowStart;
    private long tuneWindowBytes;
    private boolean tuneWarmUp;
    private double tunePrevRate;
    private int tunePrevCount;
    /** Thread count for the fill phase: the configured one, or what the tuning settled on. */
    private volatile int fillThreads;
    /** File size for the fill phase of the current pass. */
    private long passChunkBytes;

    public WipeEngine(Platform platform, WipeConfig cfg, EngineListener listener) {
        this.platform = platform;
        this.cfg = cfg;
        this.listener = listener;
        this.pass = cfg.resume ? cfg.startPass : 1;
        this.targetWorkers.set(cfg.threads);
        this.fillThreads = cfg.threads;
        this.passChunkBytes = cfg.chunkBytes;
    }

    public WipeConfig config() {
        return cfg;
    }

    /** Requests a stop. Fill files are deleted before {@link #run()} returns. Safe from any thread. */
    public void cancel() {
        cancelled = true;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Pauses or continues writing. While paused the workers first fsync what they have written and
     * then wait; nothing is deleted. Safe from any thread.
     */
    public void setPaused(boolean pause) {
        synchronized (pauseLock) {
            if (paused == pause) return;
            long now = System.nanoTime();
            if (pause) {
                pausedSinceNanos = now;
            } else {
                pausedTotalNanos += now - pausedSinceNanos;
                pausedSinceNanos = 0;
            }
            paused = pause;
            pauseLock.notifyAll();
        }
        emit(pause ? EngineListener.Event.PAUSED : EngineListener.Event.CONTINUED);
    }

    public boolean isPaused() {
        return paused;
    }

    /** Runs the whole job on the calling thread. Returns DONE, CANCELLED or FAILED. */
    public Phase run() {
        runStartNanos = System.nanoTime();
        try {
            prepare();
            int first = cfg.resume ? cfg.startPass : 1;
            for (int p = first; p <= cfg.passes && !cancelled; p++) {
                startPass(p, cfg.resume && p == first, p == first);
                if (cancelled) break;
                fill();
                if (cancelled) break;
                squeeze();
                if (cancelled) break;
                sweep();
                if (cancelled) break;
                measureUnreachable();
                long bytes = passBytes.get();
                deleteFillFiles();
                emit(EngineListener.Event.PASS_DONE, p, bytes, secondsSince(passStartNanos), unreachableBytes);
            }
            if (cancelled) {
                deleteFillFiles();
                removeDirIfEmpty();
                phase = Phase.CANCELLED;
                emit(EngineListener.Event.CANCELLED);
            } else {
                removeDirIfEmpty();
                phase = Phase.DONE;
                emit(EngineListener.Event.DONE, previousPassesBytes + passBytes.get(), secondsSince(runStartNanos));
            }
        } catch (Throwable t) {
            failure = describe(t);
            try {
                deleteFillFiles();
                removeDirIfEmpty();
            } catch (Throwable ignored) {
                // best effort
            }
            phase = Phase.FAILED;
            emit(EngineListener.Event.FAILED, failure);
        } finally {
            endNanos = System.nanoTime();
        }
        return phase;
    }

    // ------------------------------------------------------------------ phases

    private void prepare() throws IOException {
        phase = Phase.PREPARING;
        File dir = cfg.dir;
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Cannot create folder " + dir.getAbsolutePath());
        }
        try {
            volumeBytes = platform.totalBytes(dir);
        } catch (IOException ignored) {
            volumeBytes = -1;
        }
        if (!cfg.resume) {
            File[] left = listFillFiles(dir);
            if (left.length > 0) {
                long bytes = sizeOf(left);
                int n = deleteFiles(left);
                emit(EngineListener.Event.LEFTOVERS_REMOVED, n, bytes);
            }
        }
        sampleFree();
    }

    private void startPass(int p, boolean adoptLeftovers, boolean firstPassOfRun) {
        if (!firstPassOfRun) {
            previousPassesBytes += passBytes.get();
        }
        pass = p;
        phase = Phase.PREPARING;
        permilleHigh = 0;
        passBytes.set(0);
        filesInPass.set(0);
        nextIndex.set(0);
        emaBps = 0;
        if (adoptLeftovers) {
            File[] left = listFillFiles(cfg.dir);
            long bytes = sizeOf(left);
            passBytes.set(bytes);
            filesInPass.set(left.length);
            nextIndex.set(maxIndex(left) + 1);
            emit(EngineListener.Event.RESUMED, left.length, bytes);
        }
        passStartNanos = System.nanoTime();
        lastSampleNanos = passStartNanos;
        lastSampleBytes = runBytes.get();
        lastSeenBytes = lastSampleBytes;
        lastProgressNanos = passStartNanos;
        stallReported = false;
        sampleFree();
        // On a small volume use smaller files, so that there are always enough of them for the
        // sweep to release only a part of the fill.
        long floor = Math.min(64L << 20, cfg.chunkBytes);
        passChunkBytes = Math.min(cfg.chunkBytes, Math.max(floor, Math.max(0, freeBytes) / 16));
        targetWorkers.set(fillThreads);
        listener.onPassStarting(p);
        emit(EngineListener.Event.PASS_START, p, cfg.passes, fillThreads, cfg.mode.name(),
                cfg.dir.getAbsolutePath(), cfg.adaptive, cfg.maxThreads);
    }

    private void fill() throws IOException {
        phase = Phase.FILLING;
        // Tuning is done once per run: later passes keep the thread count the first one found.
        boolean tune = cfg.adaptive && tunePrevCount == 0 && fillThreads < cfg.maxThreads;
        try {
            runWorkers(fillThreads, passChunkBytes, tune);
        } finally {
            if (tune) fillThreads = targetWorkers.get();
        }
        if (cancelled) return;
        emit(EngineListener.Event.FILL_DONE, passBytes.get(), secondsSince(passStartNanos));
    }

    /**
     * Starts {@code count} workers that write files of {@code fileBytes} until the volume is full,
     * and watches them from the calling (coordinator) thread.
     */
    private void runWorkers(int count, long fileBytes, boolean tune) throws IOException {
        diskFullHits.set(0);
        firstPassError = null;
        targetWorkers.set(count);
        List<Thread> workers = new ArrayList<>();
        for (int slot = 0; slot < count; slot++) {
            startWorker(workers, slot, fileBytes);
        }
        if (tune) {
            tuning = true;
            tunePrevRate = -1;
            tunePrevCount = count;
            tuneWarmUp = true;
            tuneWindowStart = System.nanoTime();
            tuneWindowBytes = runBytes.get();
        }
        try {
            while (true) {
                Thread alive = null;
                for (Thread t : workers) {
                    if (t.isAlive()) {
                        alive = t;
                        break;
                    }
                }
                if (alive == null) break;
                alive.join(1000);
                sample();
                if (tuning) tune(workers, fileBytes);
            }
        } catch (InterruptedException e) {
            cancel();
            Thread.currentThread().interrupt();
            return;
        } finally {
            tuning = false;
        }
        sample();
        if (cancelled) return;
        if (diskFullHits.get() == 0) {
            // Every writer stopped on a real error (EIO, EROFS, card removed...). Do not pretend it worked.
            throw new IOException(firstPassError != null ? firstPassError : "Writers stopped before the volume was full");
        }
    }

    private void startWorker(List<Thread> workers, int slot, long fileBytes) {
        Thread t = new Thread(new Worker(slot, fileBytes), "wipe-worker-" + (slot + 1));
        t.setDaemon(true);
        workers.add(t);
        t.start();
    }

    /**
     * Adaptive thread count. Flash, file system and kernel decide how many writers are useful, and
     * that differs from phone to phone, so it is measured instead of guessed: after each window the
     * speed is compared with the speed at the previous thread count. Clearly faster: double the
     * threads and measure again. Otherwise: go back to the previous count and keep it.
     */
    private void tune(List<Thread> workers, long fileBytes) {
        long now = System.nanoTime();
        if (paused || diskFullHits.get() > 0 || cancelled) {
            if (diskFullHits.get() > 0) tuning = false;
            tuneWindowStart = now;
            tuneWindowBytes = runBytes.get();
            return;
        }
        if (now - tuneWindowStart < cfg.tuneWindowMs * 1_000_000L) return;
        long bytes = runBytes.get();
        double rate = (bytes - tuneWindowBytes) / ((now - tuneWindowStart) / 1e9);
        tuneWindowStart = now;
        tuneWindowBytes = bytes;
        if (tuneWarmUp) {
            // The first window after a change still contains the burst into the page cache.
            tuneWarmUp = false;
            return;
        }
        int current = targetWorkers.get();
        boolean better = tunePrevRate < 0 || rate > tunePrevRate * TUNE_GAIN;
        if (better && current < cfg.maxThreads && !outOfMemory) {
            int next = Math.min(cfg.maxThreads, current * 2);
            tunePrevRate = rate;
            tunePrevCount = current;
            targetWorkers.set(next);
            try {
                for (int slot = current; slot < next; slot++) {
                    startWorker(workers, slot, fileBytes);
                }
            } catch (Throwable t) {
                // Could not create more threads: stay where we are.
                targetWorkers.set(current);
                settle(current, rate);
                return;
            }
            tuneWarmUp = true;
            emit(EngineListener.Event.THREADS_CHANGED, current, next, rate);
        } else if (better) {
            settle(current, rate);
        } else {
            // No real gain from the last step: the extra threads only cost memory.
            targetWorkers.set(tunePrevCount);
            emit(EngineListener.Event.THREADS_CHANGED, current, tunePrevCount, rate);
            settle(tunePrevCount, tunePrevRate);
        }
    }

    private void settle(int threads, double rate) {
        tuning = false;
        emit(EngineListener.Event.THREADS_SETTLED, threads, rate);
    }

    private void squeeze() {
        phase = Phase.SQUEEZING;
        long before = runBytes.get();
        squeezeRounds();
        emit(EngineListener.Event.SQUEEZE_DONE, runBytes.get() - before);
    }

    private void squeezeRounds() {
        byte[] buf = new byte[SQUEEZE_BIG];
        int idleRounds = 0;
        for (int round = 0; round < SQUEEZE_ROUNDS && !cancelled; round++) {
            sleepQuietly(300);
            if (!awaitUnpaused(null, null)) return;
            long roundStart = runBytes.get();
            int idx = nextIndex.getAndIncrement();
            int st = squeezeFile(new File(cfg.dir, fileName(TAIL_PREFIX, idx)), buf);
            sample();
            if (st == ST_ERROR || st == ST_CANCEL) break;
            if (runBytes.get() == roundStart) {
                if (++idleRounds >= 2) break;
            } else {
                idleRounds = 0;
            }
        }
    }

    /** See the class comment. Problems here are logged; they never fail the pass. */
    private void sweep() {
        if (!cfg.sweep) return;
        for (int round = 0; round < cfg.sweepRounds && !cancelled; round++) {
            if (!sweepOnce()) return;
        }
    }

    private boolean sweepOnce() {
        long reserve;
        try {
            reserve = platform.freeBytesIncludingReserved(cfg.dir);
        } catch (IOException e) {
            return false;
        }
        if (reserve < MIN_SWEEP_RESERVE) return false; // nothing is out of reach on this volume
        reserve = Math.min(reserve, cfg.sweepMaxReserveBytes);
        // Room for the small files: twice the reserve, so that half of it (the future holes) is
        // more than the reserve. Never more than half of what we hold.
        long want = Math.min(2 * reserve + (32L << 20), passBytes.get() / 2);
        if (want < 64L * cfg.sweepHoleBytes) return false;

        phase = Phase.SWEEPING;
        File[] files = listFillFiles(cfg.dir);
        Arrays.sort(files, (x, y) -> Integer.compare(indexOf(x), indexOf(y)));
        long released = 0;
        for (File f : files) {
            if (released >= want || cancelled) break;
            if (!f.getName().startsWith(FILL_PREFIX)) continue;
            released += forget(f);
        }
        if (released == 0 || cancelled) return false;
        emit(EngineListener.Event.SWEEP_START, reserve, released);
        sampleFree();
        long before = runBytes.get();

        // Small files until the volume is full again; every second one will become a hole.
        // Each file is fsync'ed when it is closed, so the blocks really are allocated in this
        // alternating order before any of them is released.
        targetWorkers.set(1);
        List<File> holes = new ArrayList<>();
        byte[] buf = new byte[cfg.sweepHoleBytes];
        boolean hole = true;
        int written = 0;
        while (!cancelled && awaitUnpaused(null, null)) {
            int idx = nextIndex.getAndIncrement();
            File f = new File(cfg.dir, fileName(FILL_PREFIX, idx));
            if (writeFile(f, buf, buf.length, 0, idx, false) != ST_OK) break;
            if (hole) holes.add(f);
            hole = !hole;
            if (++written % 32 == 0) sample();
        }
        if (cancelled) return false;
        for (File f : holes) {
            forget(f);
        }
        sampleFree();

        // Fill once more with large files: the allocator prefers what is left of the reserve.
        try {
            runWorkers(1, passChunkBytes, false);
        } catch (IOException e) {
            emit(EngineListener.Event.SYNC_WARNING, describe(e));
        }
        if (cancelled) return false;
        squeezeRounds();
        emit(EngineListener.Event.SWEEP_DONE, runBytes.get() - before);
        return true;
    }

    /** Deletes a fill file and takes it out of the pass counters. Returns its size, 0 if it stays. */
    private long forget(File f) {
        long len = f.length();
        if (!f.delete()) return 0;
        passBytes.addAndGet(-len);
        filesInPass.decrementAndGet();
        return len;
    }

    private void measureUnreachable() {
        try {
            // The volume is now as full as this app can make it: whatever is still free is reserved
            // for the system. Report it instead of pretending it does not exist.
            unreachableBytes = Math.max(0, platform.freeBytesIncludingReserved(cfg.dir));
        } catch (IOException e) {
            unreachableBytes = -1;
        }
    }

    private void deleteFillFiles() {
        phase = Phase.DELETING;
        File[] files = listFillFiles(cfg.dir);
        long bytes = 0;
        int n = 0;
        for (File f : files) {
            long len = f.length();
            if (f.delete()) {
                n++;
                bytes += len;
            } else if (f.exists()) {
                emit(EngineListener.Event.DELETE_FAILED, f.getName());
            }
        }
        emit(EngineListener.Event.DELETED, n, bytes);
        sampleFree();
    }

    private void removeDirIfEmpty() {
        String[] rest = cfg.dir.list();
        if (rest != null && rest.length == 0) {
            //noinspection ResultOfMethodCallIgnored
            cfg.dir.delete();
        }
    }

    // ------------------------------------------------------------------ writing

    private final class Worker implements Runnable {
        private final int slot;
        private final long fileBytes;

        Worker(int slot, long fileBytes) {
            this.slot = slot;
            this.fileBytes = fileBytes;
        }

        @Override
        public void run() {
            activeWorkers.incrementAndGet();
            try {
                byte[] buf = new byte[cfg.bufferBytes];
                while (!cancelled && slot < targetWorkers.get()) {
                    if (!awaitUnpaused(null, null)) break;
                    int idx = nextIndex.getAndIncrement();
                    File f = new File(cfg.dir, fileName(FILL_PREFIX, idx));
                    int st = writeFile(f, buf, fileBytes, slot, idx, true);
                    if (st == ST_FULL) {
                        diskFullHits.incrementAndGet();
                        break;
                    }
                    if (st != ST_OK && st != ST_LIMIT) break;
                    // ST_OK (file complete) or ST_LIMIT (file system size limit): next file.
                }
            } catch (OutOfMemoryError e) {
                // No memory for another buffer: the remaining workers carry on, tuning stops adding.
                outOfMemory = true;
                recordError(slot + 1, e);
            } catch (Throwable t) {
                recordError(slot + 1, t);
            } finally {
                activeWorkers.decrementAndGet();
            }
        }
    }

    /** Per-thread fsync interval: the dirty budget is shared by all running threads. */
    private long syncInterval() {
        long share = cfg.dirtyBudgetBytes / Math.max(1, targetWorkers.get());
        return Math.max(Math.min(MIN_SYNC_INTERVAL, cfg.syncIntervalBytes), Math.min(cfg.syncIntervalBytes, share));
    }

    /**
     * Blocks while the engine is paused. Before waiting, the given file is fsync'ed so that
     * everything written so far is on flash. Returns false when the engine was cancelled.
     */
    private boolean awaitUnpaused(Platform.Sink sink, File f) {
        if (!paused) return !cancelled;
        if (sink != null) {
            try {
                sink.sync();
            } catch (IOException e) {
                emit(EngineListener.Event.SYNC_WARNING, f.getName() + ": " + describe(e));
            }
        }
        synchronized (pauseLock) {
            while (paused && !cancelled) {
                try {
                    pauseLock.wait(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return !cancelled;
    }

    /**
     * Writes one fill file of at most {@code limit} bytes.
     *
     * @param report log when this file hits the end of the volume
     */
    private int writeFile(File f, byte[] buf, long limit, int slot, int idx, boolean report) {
        int workerId = slot + 1;
        Platform.Sink sink;
        try {
            sink = platform.create(f);
        } catch (NoSpaceException e) {
            // Not even room for a new inode / node block: the volume is full.
            if (report) emit(EngineListener.Event.WORKER_DISK_FULL, workerId, idx, 0L);
            return ST_FULL;
        } catch (IOException e) {
            recordError(workerId, e);
            return ST_ERROR;
        }
        filesInPass.incrementAndGet();
        int writeSize = buf.length;
        DataSource src = DataSource.create(cfg.mode, writeSize);
        long inFile = 0;
        long sinceSync = 0;
        int status = ST_OK;
        try {
            while (inFile < limit) {
                if (cancelled) {
                    status = ST_CANCEL;
                    break;
                }
                if (slot >= targetWorkers.get()) {
                    status = ST_RETIRED;
                    break;
                }
                if (paused) {
                    if (!awaitUnpaused(sink, f)) {
                        status = ST_CANCEL;
                        break;
                    }
                    sinceSync = 0;
                }
                int want = (int) Math.min(writeSize, limit - inFile);
                src.fill(buf, want);
                int off = 0;
                while (off < want) {
                    int n = sink.write(buf, off, want - off);
                    off += n;
                    inFile += n;
                    sinceSync += n;
                    passBytes.addAndGet(n);
                    runBytes.addAndGet(n);
                }
                if (sinceSync >= syncInterval()) {
                    sink.sync();
                    sinceSync = 0;
                }
            }
        } catch (NoSpaceException e) {
            status = ST_FULL;
        } catch (FileTooLargeException e) {
            if (inFile == 0) {
                // The file system refuses even an empty file: rolling over would loop forever.
                status = ST_ERROR;
                recordError(workerId, e);
            } else {
                status = ST_LIMIT;
                emit(EngineListener.Event.FILE_SIZE_LIMIT, workerId, inFile);
            }
        } catch (IOException | RuntimeException e) {
            status = ST_ERROR;
            recordError(workerId, e);
        } finally {
            closeSink(sink, f, status != ST_CANCEL);
        }
        if (status == ST_FULL && report) {
            emit(EngineListener.Event.WORKER_DISK_FULL, workerId, idx, inFile);
        }
        return status;
    }

    /** One squeeze round: 64 KiB writes until full, then 4 KiB writes until full, all into one file. */
    private int squeezeFile(File f, byte[] buf) {
        Platform.Sink sink;
        try {
            sink = platform.create(f);
        } catch (NoSpaceException e) {
            return ST_FULL;
        } catch (IOException e) {
            emit(EngineListener.Event.SYNC_WARNING, f.getName() + ": " + e.getMessage());
            return ST_ERROR;
        }
        filesInPass.incrementAndGet();
        DataSource src = DataSource.create(cfg.mode, buf.length);
        int status = ST_FULL;
        try {
            for (int size : new int[]{SQUEEZE_BIG, SQUEEZE_SMALL}) {
                try {
                    //noinspection InfiniteLoopStatement
                    while (true) {
                        if (cancelled || (paused && !awaitUnpaused(sink, f))) {
                            status = ST_CANCEL;
                            return status;
                        }
                        src.fill(buf, size);
                        int off = 0;
                        while (off < size) {
                            int n = sink.write(buf, off, size - off);
                            off += n;
                            passBytes.addAndGet(n);
                            runBytes.addAndGet(n);
                        }
                    }
                } catch (NoSpaceException | FileTooLargeException e) {
                    // next, smaller write size
                }
            }
        } catch (IOException | RuntimeException e) {
            status = ST_ERROR;
            emit(EngineListener.Event.SYNC_WARNING, f.getName() + ": " + describe(e));
        } finally {
            closeSink(sink, f, status != ST_CANCEL);
        }
        return status;
    }

    /**
     * fsync before close is what makes the overwrite real: a file that is deleted while its data
     * still sits in the page cache never gets that data written to flash.
     */
    private void closeSink(Platform.Sink sink, File f, boolean sync) {
        if (sync) {
            try {
                sink.sync();
            } catch (IOException e) {
                emit(EngineListener.Event.SYNC_WARNING, f.getName() + ": " + e.getMessage());
            }
        }
        try {
            sink.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    private void recordError(int workerId, Throwable t) {
        totalErrors.incrementAndGet();
        String msg = describe(t);
        if (firstPassError == null) firstPassError = msg;
        emit(EngineListener.Event.WORKER_ERROR, workerId, msg);
    }

    // ------------------------------------------------------------------ progress

    private void sample() {
        long now = System.nanoTime();
        long b = runBytes.get();
        double dt = (now - lastSampleNanos) / 1e9;
        if (dt >= 0.5) {
            double inst = Math.max(0, (b - lastSampleBytes) / dt);
            double prev = emaBps;
            emaBps = prev <= 0 ? inst : prev * 0.7 + inst * 0.3;
            lastSampleNanos = now;
            lastSampleBytes = b;
        }
        if (b != lastSeenBytes || paused) {
            lastSeenBytes = b;
            lastProgressNanos = now;
            stallReported = false;
        } else if (phase.isWriting() && !stallReported
                && now - lastProgressNanos >= cfg.stallReportMs * 1_000_000L) {
            stallReported = true;
            emit(EngineListener.Event.STALLED, (now - lastProgressNanos) / 1_000_000_000L);
        }
        sampleFree();
    }

    private void sampleFree() {
        try {
            freeBytes = platform.freeBytes(cfg.dir);
        } catch (IOException ignored) {
            // keep previous value
        }
    }

    public Snapshot snapshot() {
        long now = endNanos != 0 ? endNanos : System.nanoTime();
        long start = runStartNanos;
        long pausedNanos;
        boolean isPaused;
        synchronized (pauseLock) {
            isPaused = paused;
            pausedNanos = pausedTotalNanos + (isPaused ? Math.max(0, now - pausedSinceNanos) : 0);
        }
        Phase ph = phase;
        Snapshot s = new Snapshot();
        s.phase = ph;
        s.pass = pass;
        s.passes = cfg.passes;
        s.passBytes = Math.max(0, passBytes.get());
        s.totalBytes = previousPassesBytes + s.passBytes;
        s.runBytes = runBytes.get();
        s.freeBytes = freeBytes;
        s.volumeBytes = volumeBytes;
        s.elapsedMs = start == 0 ? 0 : (now - start) / 1_000_000L;
        s.activeMs = Math.max(0, s.elapsedMs - pausedNanos / 1_000_000L);
        s.paused = isPaused && !ph.isTerminal();
        boolean writing = ph.isWriting() && !s.paused;
        s.speedBps = writing ? emaBps : 0;
        s.avgBps = s.activeMs > 500 ? s.runBytes * 1000.0 / s.activeMs : 0;
        s.etaMs = (writing && s.speedBps > 1 && s.freeBytes >= 0) ? (long) (s.freeBytes / s.speedBps * 1000.0) : -1;
        s.activeWorkers = activeWorkers.get();
        s.threads = targetWorkers.get();
        s.fillThreads = fillThreads;
        s.maxThreads = cfg.maxThreads;
        s.adaptive = cfg.adaptive;
        s.tuning = tuning;
        s.filesInPass = Math.max(0, filesInPass.get());
        s.errors = totalErrors.get();
        s.failure = failure;
        s.unreachableBytes = unreachableBytes;
        long lp = lastProgressNanos;
        s.stalledMs = (writing && lp != 0) ? Math.max(0, (now - lp) / 1_000_000L) : 0;
        s.permilleFloor = permilleHigh;
        if (ph.isWriting()) {
            int pm = s.permille();
            if (pm > permilleHigh) permilleHigh = pm; // a lost update between two readers only delays it
        }
        return s;
    }

    private void emit(EngineListener.Event e, Object... args) {
        try {
            listener.onEvent(e, args);
        } catch (Throwable ignored) {
            // a logging problem must never stop the wipe
        }
    }

    // ------------------------------------------------------------------ helpers (also used by the UI)

    public static String fileName(String prefix, int index) {
        return String.format(Locale.ROOT, "%s%06d%s", prefix, index, SUFFIX);
    }

    /** True only for names this engine creates: fill-NNNNNN.bin / tail-NNNNNN.bin. */
    public static boolean isFillName(String name) {
        String prefix;
        if (name.startsWith(FILL_PREFIX)) prefix = FILL_PREFIX;
        else if (name.startsWith(TAIL_PREFIX)) prefix = TAIL_PREFIX;
        else return false;
        if (!name.endsWith(SUFFIX)) return false;
        String mid = name.substring(prefix.length(), name.length() - SUFFIX.length());
        if (mid.isEmpty() || mid.length() > 9) return false;
        for (int i = 0; i < mid.length(); i++) {
            char c = mid.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    public static File[] listFillFiles(File dir) {
        File[] all = dir.listFiles();
        if (all == null) return new File[0];
        List<File> out = new ArrayList<>();
        for (File f : all) {
            if (isFillName(f.getName()) && f.isFile()) out.add(f);
        }
        return out.toArray(new File[0]);
    }

    public static long sizeOf(File[] files) {
        long sum = 0;
        for (File f : files) sum += f.length();
        return sum;
    }

    /** Deletes the given files, returns how many were removed. */
    public static int deleteFiles(File[] files) {
        int n = 0;
        for (File f : files) {
            if (f.delete()) n++;
        }
        return n;
    }

    /** Number in a fill file name, -1 if it has none. */
    static int indexOf(File f) {
        String name = f.getName();
        String prefix = name.startsWith(FILL_PREFIX) ? FILL_PREFIX : TAIL_PREFIX;
        try {
            return Integer.parseInt(name.substring(prefix.length(), name.length() - SUFFIX.length()));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static int maxIndex(File[] files) {
        int max = -1;
        for (File f : files) {
            max = Math.max(max, indexOf(f));
        }
        return max;
    }

    private static double secondsSince(long startNanos) {
        return startNanos == 0 ? 0 : (System.nanoTime() - startNanos) / 1e9;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String describe(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
