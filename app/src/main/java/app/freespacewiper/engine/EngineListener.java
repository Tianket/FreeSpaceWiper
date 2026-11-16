package app.freespacewiper.engine;

/**
 * Receives log events from the engine. Events carry raw values; the UI layer turns them into
 * localized text (Chinese / English). Called from engine threads: implementations must be thread safe.
 */
public interface EngineListener {

    enum Event {
        /** args: pass, passes, threads, mode, dir, adaptive (Boolean), maxThreads */
        PASS_START,
        /** args: files, bytes */
        RESUMED,
        /** args: files, bytes */
        LEFTOVERS_REMOVED,
        /** args: workerId, fileIndex, bytesInFile */
        WORKER_DISK_FULL,
        /** args: workerId, message */
        WORKER_ERROR,
        /** args: workerId, bytesInFile */
        FILE_SIZE_LIMIT,
        /** args: message */
        SYNC_WARNING,
        /** args: fromThreads, toThreads, bytesPerSecond measured with fromThreads */
        THREADS_CHANGED,
        /** args: threads, bytesPerSecond */
        THREADS_SETTLED,
        /** args: none */
        PAUSED,
        /** args: none */
        CONTINUED,
        /** args: bytes, seconds */
        FILL_DONE,
        /** args: seconds without a single byte written */
        STALLED,
        /** args: bytes */
        SQUEEZE_DONE,
        /** args: reserved bytes the sweep goes after, bytes released to make room */
        SWEEP_START,
        /** args: bytes written again */
        SWEEP_DONE,
        /** args: files, bytes */
        DELETED,
        /** args: fileName */
        DELETE_FAILED,
        /** args: pass, bytes, seconds, unreachableBytes (-1 if unknown) */
        PASS_DONE,
        /** args: none */
        CANCELLED,
        /** args: message */
        FAILED,
        /** args: totalBytes, seconds */
        DONE
    }

    void onEvent(Event event, Object... args);

    /** Called once per pass, before writing starts, while there is still free space (safe to persist state). */
    void onPassStarting(int pass);
}
