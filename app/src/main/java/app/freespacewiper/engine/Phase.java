package app.freespacewiper.engine;

public enum Phase {
    /** Creating the work folder, removing leftovers or scanning them for a resume. */
    PREPARING,
    /** Worker threads write large files until the volume reports ENOSPC. */
    FILLING,
    /** Single thread squeezes the last few KB/MB out with small writes after everything was fsync'ed. */
    SQUEEZING,
    /** The oldest fill data is released and written again, to reach blocks the first fill left free. */
    SWEEPING,
    /** Removing the fill files so the space becomes usable again. */
    DELETING,
    DONE,
    CANCELLED,
    FAILED;

    public boolean isTerminal() {
        return this == DONE || this == CANCELLED || this == FAILED;
    }

    /** Phases in which data is being written and speed / remaining time are meaningful. */
    public boolean isWriting() {
        return this == FILLING || this == SQUEEZING || this == SWEEPING;
    }
}
