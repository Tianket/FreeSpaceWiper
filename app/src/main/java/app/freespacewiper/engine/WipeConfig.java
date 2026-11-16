package app.freespacewiper.engine;

import java.io.File;

/** Immutable job description. */
public final class WipeConfig {

    public static final int MAX_THREADS = 32;
    public static final int MAX_PASSES = 3;

    /** 1 GiB per file: well below FAT32's 4 GiB limit, few enough files to delete quickly. */
    public static final long DEFAULT_CHUNK = 1L << 30;
    /**
     * 1 MiB per write() call. Flash, not the syscall rate, limits the speed (a few hundred calls per
     * second cost nothing), while small buffers keep the Java heap at ~1 MiB per thread and make
     * Stop and Pause react at once.
     */
    public static final int DEFAULT_BUFFER = 1 << 20;
    /** Upper bound for the per-thread fsync interval. */
    public static final long DEFAULT_SYNC_INTERVAL = 128L << 20;
    /**
     * Data that may sit in the page cache without having been fsync'ed, summed over all threads.
     * Each thread syncs after (budget / threads) bytes, so adding threads does not add memory pressure.
     */
    public static final long DEFAULT_DIRTY_BUDGET = 512L << 20;
    /** Size of the small files (and so of the holes) the sweep works with. */
    public static final int DEFAULT_SWEEP_HOLE = 64 << 10;

    /** Work folder. Everything inside that matches the fill-file naming is owned by the engine. */
    public final File dir;
    public final WipeMode mode;
    /** Number of writer threads; with {@link #adaptive} the number to start with. */
    public final int threads;
    /** Upper limit for {@link #adaptive} tuning (equal to {@link #threads} otherwise). */
    public final int maxThreads;
    /** Raise the thread count while the measured write speed keeps improving. */
    public final boolean adaptive;
    public final int passes;
    /** Continue from fill files left behind by an interrupted run instead of deleting them first. */
    public final boolean resume;
    /** 1-based pass to start at (only meaningful when resuming). */
    public final int startPass;
    public final long chunkBytes;
    public final int bufferBytes;
    public final long syncIntervalBytes;
    public final long dirtyBudgetBytes;
    /** A pause this long without a single byte written is logged once (flash clean-up can take a while). */
    public final long stallReportMs;
    /** Length of one measuring window of the adaptive tuning. */
    public final long tuneWindowMs;
    /** Run the sweep that reaches into the space reserved for the system (see WipeEngine). */
    public final boolean sweep;
    public final int sweepHoleBytes;
    /** One round already reaches most of the reserve; measured on ext4, three leave nothing behind. */
    public final int sweepRounds;
    /** The sweep handles at most this much reserved space (it needs twice as much in small files). */
    public final long sweepMaxReserveBytes;

    private WipeConfig(Builder b) {
        this.dir = b.dir;
        this.mode = b.mode;
        this.threads = clamp(b.threads, 1, MAX_THREADS);
        this.adaptive = b.adaptive;
        this.maxThreads = b.adaptive ? clamp(b.maxThreads, this.threads, MAX_THREADS) : this.threads;
        this.passes = clamp(b.passes, 1, MAX_PASSES);
        this.resume = b.resume;
        this.startPass = clamp(b.startPass, 1, this.passes);
        this.chunkBytes = Math.max(b.chunkBytes, 1L << 20);
        this.bufferBytes = Math.max(4096, Math.min(b.bufferBytes, 64 << 20)) & ~4095;
        this.syncIntervalBytes = Math.max(b.syncIntervalBytes, this.bufferBytes);
        this.dirtyBudgetBytes = Math.max(b.dirtyBudgetBytes, this.bufferBytes);
        this.stallReportMs = Math.max(b.stallReportMs, 100);
        this.tuneWindowMs = Math.max(b.tuneWindowMs, 50);
        this.sweep = b.sweep;
        this.sweepHoleBytes = Math.max(4096, Math.min(b.sweepHoleBytes, 4 << 20)) & ~4095;
        this.sweepMaxReserveBytes = Math.max(b.sweepMaxReserveBytes, 0);
        this.sweepRounds = clamp(b.sweepRounds, 1, 4);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static Builder builder(File dir) {
        return new Builder(dir);
    }

    public static final class Builder {
        private final File dir;
        private WipeMode mode = WipeMode.RANDOM;
        private int threads = 4;
        private int maxThreads = 4;
        private boolean adaptive;
        private int passes = 1;
        private boolean resume;
        private int startPass = 1;
        private long chunkBytes = DEFAULT_CHUNK;
        private int bufferBytes = DEFAULT_BUFFER;
        private long syncIntervalBytes = DEFAULT_SYNC_INTERVAL;
        private long dirtyBudgetBytes = DEFAULT_DIRTY_BUDGET;
        private long stallReportMs = 20_000;
        private long tuneWindowMs = 5_000;
        private boolean sweep = true;
        private int sweepHoleBytes = DEFAULT_SWEEP_HOLE;
        private long sweepMaxReserveBytes = 1L << 30;
        private int sweepRounds = 3;

        private Builder(File dir) {
            if (dir == null) throw new IllegalArgumentException("dir == null");
            this.dir = dir;
        }

        public Builder mode(WipeMode v) { this.mode = v == null ? WipeMode.RANDOM : v; return this; }
        public Builder threads(int v) { this.threads = v; return this; }
        /** Start with {@code start} threads and let the engine go up to {@code max} while that is faster. */
        public Builder adaptiveThreads(int start, int max) {
            this.threads = start;
            this.maxThreads = max;
            this.adaptive = true;
            return this;
        }
        public Builder passes(int v) { this.passes = v; return this; }
        public Builder resume(boolean v) { this.resume = v; return this; }
        public Builder startPass(int v) { this.startPass = v; return this; }
        public Builder chunkBytes(long v) { this.chunkBytes = v; return this; }
        public Builder bufferBytes(int v) { this.bufferBytes = v; return this; }
        public Builder syncIntervalBytes(long v) { this.syncIntervalBytes = v; return this; }
        public Builder dirtyBudgetBytes(long v) { this.dirtyBudgetBytes = v; return this; }
        public Builder stallReportMs(long v) { this.stallReportMs = v; return this; }
        public Builder tuneWindowMs(long v) { this.tuneWindowMs = v; return this; }
        public Builder sweep(boolean v) { this.sweep = v; return this; }
        public Builder sweepHoleBytes(int v) { this.sweepHoleBytes = v; return this; }
        public Builder sweepRounds(int v) { this.sweepRounds = v; return this; }
        public Builder sweepMaxReserveBytes(long v) { this.sweepMaxReserveBytes = v; return this; }

        public WipeConfig build() {
            return new WipeConfig(this);
        }
    }
}
