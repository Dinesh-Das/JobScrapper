package org.dinesh.util;

/**
 * Tracks only page-ordered Phase 1 outcomes. A gap resets the streak because a
 * checkpointed page between two results is known to have contained jobs.
 */
public final class Phase1ProgressTracker {
    public enum Decision {
        CONTINUE,
        PAUSE_AFTER_EMPTY_PAGES,
        PAUSE_AFTER_FAILURES
    }

    private final int emptyPageLimit;
    private final int failureLimit;
    private int consecutiveEmpty;
    private int consecutiveFailures;
    private int lastPage = Integer.MIN_VALUE;

    public Phase1ProgressTracker(int emptyPageLimit, int failureLimit) {
        this.emptyPageLimit = Math.max(1, emptyPageLimit);
        this.failureLimit = Math.max(1, failureLimit);
    }

    public Decision record(int page, boolean success, boolean confirmedEmpty) {
        if (lastPage != Integer.MIN_VALUE && page != lastPage + 1) {
            consecutiveEmpty = 0;
            consecutiveFailures = 0;
        }
        lastPage = page;

        if (!success) {
            consecutiveEmpty = 0;
            consecutiveFailures++;
            return consecutiveFailures >= failureLimit
                    ? Decision.PAUSE_AFTER_FAILURES
                    : Decision.CONTINUE;
        }

        consecutiveFailures = 0;
        if (confirmedEmpty) {
            consecutiveEmpty++;
            return consecutiveEmpty >= emptyPageLimit
                    ? Decision.PAUSE_AFTER_EMPTY_PAGES
                    : Decision.CONTINUE;
        }

        consecutiveEmpty = 0;
        return Decision.CONTINUE;
    }

    public int getConsecutiveEmpty() {
        return consecutiveEmpty;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }
}
