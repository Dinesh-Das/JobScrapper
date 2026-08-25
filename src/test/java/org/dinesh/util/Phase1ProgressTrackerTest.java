package org.dinesh.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Phase1ProgressTrackerTest {
    @Test
    void pausesOnlyAfterConfirmedConsecutiveEmptyPages() {
        Phase1ProgressTracker tracker = new Phase1ProgressTracker(3, 5);

        assertEquals(Phase1ProgressTracker.Decision.CONTINUE, tracker.record(1, true, true));
        assertEquals(Phase1ProgressTracker.Decision.CONTINUE, tracker.record(2, true, true));
        assertEquals(Phase1ProgressTracker.Decision.PAUSE_AFTER_EMPTY_PAGES,
                tracker.record(3, true, true));
    }

    @Test
    void nonEmptyPageClearsAnEmptyPagePause() {
        Phase1ProgressTracker tracker = new Phase1ProgressTracker(2, 5);

        tracker.record(1, true, true);
        assertEquals(Phase1ProgressTracker.Decision.PAUSE_AFTER_EMPTY_PAGES,
                tracker.record(2, true, true));
        assertEquals(Phase1ProgressTracker.Decision.CONTINUE, tracker.record(3, true, false));
        assertEquals(0, tracker.getConsecutiveEmpty());
    }

    @Test
    void failuresAreTrackedSeparatelyAndRemainRetryable() {
        Phase1ProgressTracker tracker = new Phase1ProgressTracker(2, 2);

        assertEquals(Phase1ProgressTracker.Decision.CONTINUE, tracker.record(1, false, false));
        assertEquals(Phase1ProgressTracker.Decision.PAUSE_AFTER_FAILURES,
                tracker.record(2, false, false));
        assertEquals(0, tracker.getConsecutiveEmpty());
    }

    @Test
    void pageGapBreaksAConsecutiveStreak() {
        Phase1ProgressTracker tracker = new Phase1ProgressTracker(2, 5);

        tracker.record(1, true, true);
        assertEquals(Phase1ProgressTracker.Decision.CONTINUE, tracker.record(3, true, true));
        assertEquals(1, tracker.getConsecutiveEmpty());
    }
}
