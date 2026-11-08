package com.serenesailing.hold;

/**
 * When to rescan one ship. Pure Java so the scheduling rules can be unit tested.
 *
 * <ul>
 *   <li>A block change marks the ship dirty; it is rescanned at once, but never more often than {@code minSpacing}.</li>
 *   <li>The first scan after a change that has not been applied yet arms a few follow-up scans, so the two-scan debounce is
 *       satisfied within a fraction of a second instead of at the next background interval.</li>
 *   <li>Follow-up scans do NOT re-arm themselves (only a real block change does), so a hash that never settles costs a
 *       bounded number of scans.</li>
 * </ul>
 */
public final class HoldScanSchedule {

    /** Follow-up scans armed by a block change. */
    public static final int MAX_FOLLOW_UPS = 4;

    private volatile boolean dirty;
    private boolean followUpScan;
    private int followUps;
    private long lastScanTick = Long.MIN_VALUE;

    /** A block of the ship changed (called from the server's block events). */
    public void markChanged() {
        followUpScan = false;
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

    /**
     * Is a scan allowed now?
     *
     * @param fullPass this is the periodic background pass, which scans every ship regardless of dirtiness
     */
    public boolean due(long now, boolean fullPass, int minSpacing) {
        if (fullPass) {
            return true;
        }
        return dirty && (lastScanTick == Long.MIN_VALUE || now < lastScanTick || now - lastScanTick >= minSpacing);
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
     */
    public void afterScan(boolean applied, boolean pendingDifferent, boolean external) {
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

