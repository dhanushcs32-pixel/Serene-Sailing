package com.serenesailing.hold;

/**
 * When to rescan one ship. Pure Java so the scheduling rules can be unit tested.
 *
 * <ul>
 *   <li><b>Staggered:</b> a ship's first scan is offset by a per-ship seed, so several ships never all scan on the same
 *       tick.</li>
 *   <li><b>Adaptive:</b> every scan that finds nothing new makes the next gap longer (base, then 3x base, then the
 *       maximum). A ship that has not changed costs almost nothing; any change snaps it back to the base rate.</li>
 *   <li><b>Reactive:</b> a block change marks the ship dirty; it is rescanned at once, but never more often than
 *       {@code minSpacing}. The first scan after a change that has not been applied yet arms a few follow-up scans, so
 *       the two-scan debounce is satisfied within a fraction of a second. Follow-ups do NOT re-arm themselves (only a real
 *       block change does), so a hash that never settles costs a bounded number of scans.</li>
 * </ul>
 */
public final class HoldScanSchedule {

    /** Follow-up scans armed by a block change. */
    public static final int MAX_FOLLOW_UPS = 4;
    /** Consecutive unchanged scans before the gap grows to 3x the base interval. */
    public static final int STABLE_FOR_FIRST_BACKOFF = 3;
    /** Consecutive unchanged scans before the gap grows to the maximum interval. */
    public static final int STABLE_FOR_MAX_BACKOFF = 8;

    private volatile boolean dirty;
    private boolean followUpScan;
    private int followUps;
    private boolean initialised;
    private long lastScanTick = Long.MIN_VALUE;
    private long nextScanTick;
    private int stableScans;

    /** Sets the first scan time, offset by {@code seed} within one base interval. Does nothing after the first call. */
    public void initialise(long now, int baseInterval, int seed) {
        if (!initialised) {
            initialised = true;
            nextScanTick = now + Math.floorMod(seed, Math.max(1, baseInterval));
        }
    }

    /** A block of the ship changed (called from block-change hooks). */
    public void markChanged() {
        followUpScan = false;
        stableScans = 0;
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Forget a pending change (the ship left the scan range). */
    public void clearDirty() {
        dirty = false;
        followUpScan = false;
        followUps = 0;
    }

    public long nextScanTick() {
        return nextScanTick;
    }

    public int stableScans() {
        return stableScans;
    }

    /** The scan could not run (plot not synced, too big ...): try again after {@code interval} ticks. */
    public void defer(long now, int interval) {
        nextScanTick = now + interval;
    }

    /** Is a scan allowed now? */
    public boolean due(long now, int minSpacing) {
        if (!initialised) {
            return false;
        }
        if (dirty) {
            return lastScanTick == Long.MIN_VALUE || now < lastScanTick || now - lastScanTick >= minSpacing;
        }
        return now >= nextScanTick || now < lastScanTick;
    }

    /**
     * Call right before scanning.
     *
     * @return true if this scan reacts to a real block change (as opposed to the timer or one of our own follow-ups)
     */
    public boolean beginScan(long now) {
        final boolean external = dirty && !followUpScan;
        dirty = false;
        followUpScan = false;
        lastScanTick = now;
        return external;
    }

    /**
     * Call after scanning.
     *
     * @param applied          the debounce accepted this scan's volume
     * @param pendingDifferent the scan differs from what is applied but the debounce has not accepted it yet
     * @param external         the value {@link #beginScan} returned
     * @param changed          the scan's result differs from the previous scan's (volume or class)
     * @param baseInterval     ticks between scans of a ship that is still changing
     * @param maxInterval      ticks between scans of a long-stable ship
     */
    public void afterScan(long now, boolean applied, boolean pendingDifferent, boolean external, boolean changed,
                          int baseInterval, int maxInterval) {
        stableScans = (changed || applied || pendingDifferent) ? 0 : stableScans + 1;
        final int interval = stableScans >= STABLE_FOR_MAX_BACKOFF ? maxInterval
                : stableScans >= STABLE_FOR_FIRST_BACKOFF ? Math.min(maxInterval, baseInterval * 3) : baseInterval;
        nextScanTick = now + interval;

        if (applied || !pendingDifferent) {
            followUps = 0;
            return;
        }
        if (external) {
            followUps = MAX_FOLLOW_UPS;
        }
        if (followUps > 0) {
            followUps--;
            dirty = true;
            followUpScan = true;
        }
    }
}
