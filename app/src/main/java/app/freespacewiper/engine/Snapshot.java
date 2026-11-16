package app.freespacewiper.engine;

/** Progress snapshot: filled in once by the engine, read-only for everyone else. */
public final class Snapshot {
    public Phase phase;
    public int pass;
    public int passes;
    /** Bytes held in fill files in the current pass (includes leftovers adopted on resume). */
    public long passBytes;
    /** Free space overwritten over all passes. */
    public long totalBytes;
    /** Bytes this run has written itself (excludes adopted leftovers, includes the sweep). */
    public long runBytes;
    /** Free bytes the app can still allocate, sampled about once a second; -1 if unknown. */
    public long freeBytes;
    /** Size of the whole volume; -1 if unknown. */
    public long volumeBytes;
    /** Smoothed current speed in bytes per second. */
    public double speedBps;
    /** Average speed of this run in bytes per second, not counting paused time. */
    public double avgBps;
    public long elapsedMs;
    /** Elapsed time without the time spent paused. */
    public long activeMs;
    /** Estimated time until the volume is full; -1 if unknown. */
    public long etaMs;
    public int activeWorkers;
    /** Writer threads currently wanted (changes while the adaptive tuning is running). */
    public int threads;
    /** Writer threads of the fill phase: the fixed count, or what the adaptive tuning arrived at. */
    public int fillThreads;
    public int maxThreads;
    public boolean adaptive;
    /** The adaptive tuning is still trying thread counts. */
    public boolean tuning;
    public boolean paused;
    public int filesInPass;
    public int errors;
    /** Set when phase == FAILED. */
    public String failure;
    /**
     * Free space that was left when the volume was full for this app (blocks reserved for the
     * system that an ordinary app cannot write), from the last completed fill; -1 if unknown.
     */
    public long unreachableBytes;
    /** While writing: how long no byte has been written (0 when data is flowing or paused). */
    public long stalledMs;

    Snapshot() {
    }

    /**
     * Highest progress shown so far in this pass. Clearing the reserved space releases data and
     * writes it again; the display must not run backwards while that happens.
     */
    public int permilleFloor;

    /**
     * Progress of the current pass in [0, 1000]; -1 when indeterminate. It never decreases within
     * a pass, and 1000 is kept for a finished job: until then the top is 999.
     */
    public int permille() {
        if (phase == Phase.DONE) return 1000;
        if (freeBytes < 0) return permilleFloor > 0 ? permilleFloor : -1;
        long target = passBytes + freeBytes;
        int now = target <= 0 ? 999 : (int) Math.min(999, (passBytes * 1000L) / target);
        return Math.max(now, permilleFloor);
    }
}
