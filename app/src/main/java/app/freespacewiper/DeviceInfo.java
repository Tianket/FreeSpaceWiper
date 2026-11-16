package app.freespacewiper;

import android.app.ActivityManager;
import android.content.Context;

import app.freespacewiper.engine.WipeConfig;

/**
 * What this phone can take: used to pick the limits for the automatic thread count and for the
 * amount of written-but-not-yet-synced data.
 *
 * The real speed limit is the flash storage, and how many parallel writers it rewards differs from
 * phone to phone. So CPU and memory only set a safe upper bound here; inside that bound the engine
 * measures the write speed and raises the thread count for as long as that makes it faster.
 */
final class DeviceInfo {

    final int cores;
    final long ramBytes;
    final int heapMb;
    /** Thread count the automatic mode starts with. */
    final int startThreads;
    /** Highest thread count the automatic mode may go to on this phone. */
    final int maxThreads;
    /** Written data allowed to wait in memory for its fsync, summed over all threads. */
    final long dirtyBudgetBytes;

    private DeviceInfo(int cores, long ramBytes, int heapMb) {
        this.cores = cores;
        this.ramBytes = ramBytes;
        this.heapMb = heapMb;

        // Writers spend most of their time waiting for storage, so several per core are fine.
        int byCpu = cores * 4;
        // Each writer holds a 1 MiB buffer on the Java heap: keep all of them within an eighth of it.
        int byHeap = Math.max(2, heapMb / 8);
        // Little memory means little room for the page cache: stay modest.
        int byRam = ramBytes < (3L << 30) ? 8 : ramBytes < (6L << 30) ? 16 : 32;
        int max = Math.min(byCpu, Math.min(byHeap, byRam));
        this.maxThreads = Math.max(2, Math.min(WipeConfig.MAX_THREADS, max));
        // Start with one writer per core (at most 8): the tuning only ever goes up from here.
        this.startThreads = Math.min(this.maxThreads, Math.max(2, Math.min(8, cores)));

        long budget = ramBytes / 16;
        this.dirtyBudgetBytes = Math.max(128L << 20, Math.min(1L << 30, budget));
    }

    static DeviceInfo get(Context c) {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        long ram = 2L << 30;
        ActivityManager am = c.getSystemService(ActivityManager.class);
        if (am != null) {
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            if (info.totalMem > 0) ram = info.totalMem;
        }
        int heapMb = (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() >> 20);
        return new DeviceInfo(cores, ram, heapMb);
    }

    /** Manual thread counts are allowed up to the engine limit, as long as their buffers fit the heap. */
    int clampManual(int threads) {
        int byHeap = Math.max(2, heapMb / 4);
        return Math.max(1, Math.min(threads, Math.min(WipeConfig.MAX_THREADS, byHeap)));
    }
}
