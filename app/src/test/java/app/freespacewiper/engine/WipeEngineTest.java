package app.freespacewiper.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs the real engine against a small simulated volume (a capacity cap enforced by the fake
 * platform on top of real temp files). Safe to run anywhere: it never writes more than a few MB.
 */
public class WipeEngineTest {

    private static final long MB = 1L << 20;

    private File root;
    private File dir;

    @Before
    public void setUp() throws IOException {
        root = Files.createTempDirectory("wipe-test").toFile();
        dir = new File(root, "wipe");
    }

    @After
    public void tearDown() {
        deleteTree(root);
    }

    @Test
    public void fillsExactlyToCapacityAndCleansUp() {
        FakePlatform p = new FakePlatform(dir, 24 * MB, Long.MAX_VALUE);
        Recorder r = new Recorder();
        WipeEngine e = new WipeEngine(p, cfg().threads(4).build(), r);
        assertEquals(Phase.DONE, e.run());
        assertEquals(24 * MB, e.snapshot().totalBytes);
        assertEquals(24 * MB, p.peak.get());
        assertEquals(0, WipeEngine.listFillFiles(dir).length);
        assertTrue(r.has(EngineListener.Event.WORKER_DISK_FULL));
        assertTrue(r.has(EngineListener.Event.DONE));
        assertEquals(0, p.unsyncedCloses.get());
    }

    @Test
    public void everyClosedFileWasFsynced() {
        FakePlatform p = new FakePlatform(dir, 12 * MB, Long.MAX_VALUE);
        WipeEngine e = new WipeEngine(p, cfg().threads(3).build(), new Recorder());
        assertEquals(Phase.DONE, e.run());
        assertEquals(0, p.unsyncedCloses.get());
    }

    @Test
    public void multiplePassesEachFillTheVolume() {
        FakePlatform p = new FakePlatform(dir, 10 * MB, Long.MAX_VALUE);
        WipeEngine e = new WipeEngine(p, cfg().threads(2).passes(3).build(), new Recorder());
        assertEquals(Phase.DONE, e.run());
        assertEquals(30 * MB, e.snapshot().totalBytes);
        assertEquals(0, WipeEngine.listFillFiles(dir).length);
    }

    @Test
    public void cancelStopsAndDeletesEverything() throws Exception {
        FakePlatform p = new FakePlatform(dir, 1024 * MB, Long.MAX_VALUE);
        p.slowDownMicros = 200;
        WipeEngine e = new WipeEngine(p, cfg().threads(4).build(), new Recorder());
        Thread t = new Thread(e::run);
        t.start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (e.snapshot().passBytes < 4 * MB && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        e.cancel();
        t.join(10_000);
        assertFalse(t.isAlive());
        assertEquals(Phase.CANCELLED, e.snapshot().phase);
        assertEquals(0, WipeEngine.listFillFiles(dir).length);
    }

    @Test
    public void resumeAdoptsLeftoversAndContinuesNumbering() throws IOException {
        assertTrue(dir.mkdirs());
        for (int i = 0; i < 3; i++) {
            writeZeros(new File(dir, WipeEngine.fileName(WipeEngine.FILL_PREFIX, i)), 2 * MB);
        }
        FakePlatform p = new FakePlatform(dir, 16 * MB, Long.MAX_VALUE);
        Recorder r = new Recorder();
        WipeEngine e = new WipeEngine(p, cfg().threads(2).resume(true).build(), r);
        assertEquals(Phase.DONE, e.run());
        assertTrue(r.has(EngineListener.Event.RESUMED));
        assertEquals(16 * MB, e.snapshot().totalBytes);
        assertFalse(p.created.contains(WipeEngine.fileName(WipeEngine.FILL_PREFIX, 0)));
        assertTrue(p.created.contains(WipeEngine.fileName(WipeEngine.FILL_PREFIX, 3)));
        assertEquals(0, WipeEngine.listFillFiles(dir).length);
    }

    @Test
    public void freshStartRemovesLeftoversFirst() throws IOException {
        assertTrue(dir.mkdirs());
        writeZeros(new File(dir, WipeEngine.fileName(WipeEngine.FILL_PREFIX, 7)), 3 * MB);
        File foreign = new File(dir, "notes.txt");
        writeZeros(foreign, 1000);
        FakePlatform p = new FakePlatform(dir, 8 * MB, Long.MAX_VALUE);
        Recorder r = new Recorder();
        WipeEngine e = new WipeEngine(p, cfg().build(), r);
        assertEquals(Phase.DONE, e.run());
        assertTrue(r.has(EngineListener.Event.LEFTOVERS_REMOVED));
        assertTrue("files that are not ours must never be deleted", foreign.exists());
    }

    @Test
    public void perFileSizeLimitRollsOverToNewFiles() {
        FakePlatform p = new FakePlatform(dir, 20 * MB, 3 * MB);
        Recorder r = new Recorder();
        WipeEngine e = new WipeEngine(p, cfg().threads(2).build(), r);
        assertEquals(Phase.DONE, e.run());
        assertEquals(20 * MB, e.snapshot().totalBytes);
        assertTrue(r.has(EngineListener.Event.FILE_SIZE_LIMIT));
    }

    @Test
    public void hardWriteErrorFailsAndCleansUp() {
        FakePlatform p = new FakePlatform(dir, 64 * MB, Long.MAX_VALUE);
        p.failAfterBytes = 5 * MB;
        WipeEngine e = new WipeEngine(p, cfg().threads(2).build(), new Recorder());
        assertEquals(Phase.FAILED, e.run());
        assertTrue(e.snapshot().failure.contains("EIO"));
        assertEquals(0, WipeEngine.listFillFiles(dir).length);
    }

    @Test
    public void reportsTheReservedSpaceItCouldNotReach() {
        FakePlatform p = new FakePlatform(dir, 12 * MB, Long.MAX_VALUE);
        p.reserved = 3 * MB;
        Recorder r = new Recorder();
        WipeEngine e = new WipeEngine(p, cfg().threads(2).build(), r);
        assertEquals(-1, e.snapshot().unreachableBytes);
        assertEquals(Phase.DONE, e.run());
        assertEquals(12 * MB, e.snapshot().totalBytes);
        assertEquals(3 * MB, e.snapshot().unreachableBytes);
    }

    @Test
    public void reportsAStallOnceAndThenFinishes() {
        FakePlatform p = new FakePlatform(dir, 8 * MB, Long.MAX_VALUE);
        p.pauseOnceAtBytes = 2 * MB;
        p.pauseMillis = 2500;
        Recorder r = new Recorder();
        WipeEngine e = new WipeEngine(p, cfg().threads(1).stallReportMs(1000).build(), r);
        assertEquals(Phase.DONE, e.run());
        assertEquals(1, r.count(EngineListener.Event.STALLED));
        assertEquals(8 * MB, e.snapshot().totalBytes);
        assertEquals(0, e.snapshot().stalledMs);
    }

    @Test
    public void sweepRunsOnlyWhereSpaceIsReserved() {
        FakePlatform withReserve = new FakePlatform(dir, 16 * MB, Long.MAX_VALUE);
        withReserve.reserved = 2 * MB;
        Recorder r1 = new Recorder();
        WipeEngine e1 = new WipeEngine(withReserve, cfg().threads(2).build(), r1);
        assertEquals(Phase.DONE, e1.run());
        assertEquals(3, r1.count(EngineListener.Event.SWEEP_START));
        assertEquals(3, r1.count(EngineListener.Event.SWEEP_DONE));
        assertEquals(16 * MB, e1.snapshot().totalBytes);
        assertEquals(16 * MB, withReserve.peak.get());
        assertTrue("the sweep writes on top of the fill", e1.snapshot().runBytes > 16 * MB);
        assertEquals(0, WipeEngine.listFillFiles(dir).length);
        assertEquals(0, withReserve.unsyncedCloses.get());

        FakePlatform noReserve = new FakePlatform(dir, 16 * MB, Long.MAX_VALUE);
        Recorder r2 = new Recorder();
        WipeEngine e2 = new WipeEngine(noReserve, cfg().threads(2).build(), r2);
        assertEquals(Phase.DONE, e2.run());
        assertEquals(0, r2.count(EngineListener.Event.SWEEP_START));
        assertEquals(16 * MB, e2.snapshot().runBytes);
    }

    @Test
    public void progressNeverRunsBackwards() throws Exception {
        // The sweep releases fill files and writes the space again: the raw fill level drops,
        // the reported progress must not.
        FakePlatform p = new FakePlatform(dir, 16 * MB, Long.MAX_VALUE);
        p.reserved = 2 * MB;
        p.slowDownMicros = 200;
        WipeEngine e = new WipeEngine(p, cfg().threads(2).build(), new Recorder());
        Thread t = new Thread(e::run);
        t.start();
        int last = -1;
        boolean sawSweep = false;
        while (t.isAlive()) {
            Snapshot s = e.snapshot();
            if (s.phase.isWriting()) {
                int pm = s.permille();
                assertTrue("progress went from " + last + " to " + pm + " while " + s.phase, pm >= last);
                assertTrue("100% is shown before the job is done", pm < 1000);
                last = pm;
                sawSweep |= s.phase == Phase.SWEEPING;
            }
            Thread.sleep(2);
        }
        t.join();
        assertTrue(sawSweep);
        assertTrue(last > 900);
        assertEquals(Phase.DONE, e.snapshot().phase);
        assertEquals(1000, e.snapshot().permille());
    }

    @Test
    public void pauseStopsWritingAndContinueFinishes() throws Exception {
        FakePlatform p = new FakePlatform(dir, 24 * MB, Long.MAX_VALUE);
        p.slowDownMicros = 300;
        Recorder r = new Recorder();
        WipeEngine e = new WipeEngine(p, cfg().threads(3).bufferBytes(16 * 1024).build(), r);
        Thread t = new Thread(e::run);
        t.start();
        waitUntil(() -> e.snapshot().passBytes > MB);
        e.setPaused(true);
        Thread.sleep(300); // writes that were already under way finish
        long first = e.snapshot().runBytes;
        Thread.sleep(400);
        Snapshot paused = e.snapshot();
        assertEquals(first, paused.runBytes);
        assertTrue(paused.paused);
        assertEquals(0, (long) paused.speedBps);
        assertTrue(first < 24 * MB);
        e.setPaused(false);
        t.join(20_000);
        assertFalse(t.isAlive());
        assertEquals(Phase.DONE, e.snapshot().phase);
        assertEquals(24 * MB, e.snapshot().totalBytes);
        assertEquals(1, r.count(EngineListener.Event.PAUSED));
        assertEquals(1, r.count(EngineListener.Event.CONTINUED));
        assertTrue(e.snapshot().activeMs < e.snapshot().elapsedMs);
    }

    @Test
    public void cancelWhilePausedCleansUp() throws Exception {
        FakePlatform p = new FakePlatform(dir, 1024 * MB, Long.MAX_VALUE);
        p.slowDownMicros = 200;
        WipeEngine e = new WipeEngine(p, cfg().threads(4).build(), new Recorder());
        Thread t = new Thread(e::run);
        t.start();
        waitUntil(() -> e.snapshot().passBytes > 2 * MB);
        e.setPaused(true);
        Thread.sleep(200);
        e.cancel();
        t.join(10_000);
        assertFalse(t.isAlive());
        assertEquals(Phase.CANCELLED, e.snapshot().phase);
        assertFalse(e.snapshot().paused);
        assertEquals(0, WipeEngine.listFillFiles(dir).length);
    }

    @Test
    public void adaptiveThreadsGrowWhileThatIsFaster() {
        // Each write waits 2 ms on its own (scales with threads) plus 0.3 ms inside a shared lock
        // (does not scale): the device saturates somewhere around 7 writers.
        FakePlatform p = new FakePlatform(dir, 96 * MB, Long.MAX_VALUE);
        p.slowDownMicros = 2000;
        p.sharedMicros = 300;
        Recorder r = new Recorder();
        WipeEngine e = new WipeEngine(p, cfg().adaptiveThreads(2, 32).bufferBytes(4096)
                .tuneWindowMs(250).sweep(false).build(), r);
        Thread t = new Thread(e::run);
        t.start();
        waitUntil(() -> r.has(EngineListener.Event.THREADS_SETTLED) || !t.isAlive());
        int settled = e.snapshot().threads;
        e.cancel();
        join(t);
        assertTrue(r.has(EngineListener.Event.THREADS_SETTLED));
        assertTrue("expected more than the 2 starting threads, got " + settled, settled >= 4);
        assertTrue("expected to stop before the limit, got " + settled, settled <= 16);
    }

    @Test
    public void adaptiveThreadsStayWhenMoreIsNotFaster() {
        // Everything happens inside the shared lock: more writers cannot be faster.
        FakePlatform p = new FakePlatform(dir, 96 * MB, Long.MAX_VALUE);
        p.sharedMicros = 1000;
        Recorder r = new Recorder();
        WipeEngine e = new WipeEngine(p, cfg().adaptiveThreads(2, 32).bufferBytes(4096)
                .tuneWindowMs(250).sweep(false).build(), r);
        Thread t = new Thread(e::run);
        t.start();
        waitUntil(() -> r.has(EngineListener.Event.THREADS_SETTLED) || !t.isAlive());
        int settled = e.snapshot().threads;
        e.cancel();
        join(t);
        assertTrue(r.has(EngineListener.Event.THREADS_SETTLED));
        assertEquals(2, settled);
        assertFalse(e.snapshot().tuning);
    }

    @Test
    public void fillNamesAreStrict() {
        assertTrue(WipeEngine.isFillName("fill-000001.bin"));
        assertTrue(WipeEngine.isFillName("tail-000123.bin"));
        assertFalse(WipeEngine.isFillName("fill-.bin"));
        assertFalse(WipeEngine.isFillName("fill-12a.bin"));
        assertFalse(WipeEngine.isFillName("fill-000001.bin.bak"));
        assertFalse(WipeEngine.isFillName("photo.jpg"));
    }

    @Test
    public void randomDataIsNotRepeated() {
        DataSource a = DataSource.create(WipeMode.RANDOM, 4096);
        DataSource b = DataSource.create(WipeMode.RANDOM, 4096);
        byte[] x = new byte[4096];
        byte[] y = new byte[4096];
        byte[] z = new byte[4096];
        a.fill(x, 4096);
        a.fill(y, 4096);
        b.fill(z, 4096);
        assertFalse(java.util.Arrays.equals(x, y));
        assertFalse(java.util.Arrays.equals(x, z));
        int zeros = 0;
        for (byte v : x) if (v == 0) zeros++;
        assertTrue("keystream should not be mostly zeros", zeros < 64);
    }

    // ------------------------------------------------------------------ helpers

    private interface Check {
        boolean ok();
    }

    private static void waitUntil(Check c) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!c.ok() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertTrue("condition not reached in time", c.ok());
    }

    private static void join(Thread t) {
        try {
            t.join(20_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertFalse(t.isAlive());
    }

    private WipeConfig.Builder cfg() {
        return WipeConfig.builder(dir).mode(WipeMode.RANDOM).chunkBytes(4 * MB)
                .bufferBytes(256 * 1024).syncIntervalBytes(MB);
    }

    private static void writeZeros(File f, long bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            byte[] b = new byte[64 * 1024];
            long left = bytes;
            while (left > 0) {
                int n = (int) Math.min(b.length, left);
                out.write(b, 0, n);
                left -= n;
            }
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static final class Recorder implements EngineListener {
        final List<Event> events = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onEvent(Event event, Object... args) {
            events.add(event);
        }

        @Override
        public void onPassStarting(int pass) {
        }

        boolean has(Event e) {
            return events.contains(e);
        }

        int count(Event e) {
            return Collections.frequency(events, e);
        }
    }

    /**
     * Real files, but a hard capacity limit shared by all of them (like a tiny disk).
     * All accounting happens under one lock so the cap is exact even with many writer threads.
     */
    static final class FakePlatform implements Platform {
        final File dir;
        final long capacity;
        final long perFileLimit;
        final AtomicLong unsyncedCloses = new AtomicLong();
        final List<String> created = Collections.synchronizedList(new ArrayList<>());
        volatile long failAfterBytes = Long.MAX_VALUE;
        /** Delay per write that each writer serves on its own (parallel part of the device). */
        volatile long slowDownMicros;
        /** Delay per write inside the shared lock (the part of the device that does not scale). */
        volatile long sharedMicros;
        /** Space that counts as free but that writers are never given (like root-reserved blocks). */
        volatile long reserved;
        volatile long pauseOnceAtBytes = Long.MAX_VALUE;
        volatile long pauseMillis;

        private final Object lock = new Object();
        private long used;
        private long totalWritten;
        final AtomicLong peak = new AtomicLong();

        FakePlatform(File dir, long capacity, long perFileLimit) {
            this.dir = dir;
            this.capacity = capacity;
            this.perFileLimit = perFileLimit;
        }

        private long recountLocked() {
            used = WipeEngine.sizeOf(WipeEngine.listFillFiles(dir));
            return used;
        }

        @Override
        public Sink create(File file) throws IOException {
            final FileOutputStream out;
            synchronized (lock) {
                recountLocked();
                created.add(file.getName());
                out = new FileOutputStream(file);
            }
            return new Sink() {
                long inFile;
                boolean dirty;

                @Override
                public int write(byte[] buf, int off, int len) throws IOException {
                    int allowed;
                    boolean pause;
                    synchronized (lock) {
                        pause = totalWritten >= pauseOnceAtBytes;
                        if (pause) pauseOnceAtBytes = Long.MAX_VALUE;
                    }
                    if (pause) {
                        try {
                            Thread.sleep(pauseMillis);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    synchronized (lock) {
                        if (totalWritten >= failAfterBytes) throw new IOException("write failed: EIO (I/O error)");
                        if (inFile >= perFileLimit) throw new FileTooLargeException("EFBIG");
                        long a = Math.min(Math.min(len, capacity - used), perFileLimit - inFile);
                        if (a <= 0) throw new NoSpaceException("ENOSPC");
                        allowed = (int) a;
                        if (sharedMicros > 0) sleepMicros(sharedMicros);
                        out.write(buf, off, allowed);
                        used += allowed;
                        totalWritten += allowed;
                        peak.accumulateAndGet(used, Math::max);
                    }
                    inFile += allowed;
                    dirty = true;
                    if (slowDownMicros > 0) sleepMicros(slowDownMicros);
                    return allowed;
                }

                @Override
                public void sync() throws IOException {
                    out.getFD().sync();
                    dirty = false;
                }

                @Override
                public void close() throws IOException {
                    boolean failedRun;
                    synchronized (lock) {
                        failedRun = totalWritten >= failAfterBytes;
                    }
                    if (dirty && !failedRun) unsyncedCloses.incrementAndGet();
                    out.close();
                }
            };
        }

        private static void sleepMicros(long micros) {
            try {
                Thread.sleep(micros / 1000, (int) (micros % 1000) * 1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public long freeBytes(File d) {
            synchronized (lock) {
                return Math.max(0, capacity - recountLocked());
            }
        }

        @Override
        public long totalBytes(File d) {
            return capacity + reserved;
        }

        @Override
        public long freeBytesIncludingReserved(File d) {
            synchronized (lock) {
                return Math.max(0, capacity - recountLocked()) + reserved;
            }
        }
    }
}
