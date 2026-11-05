package com.serenesailing.hold;

/**
 * Two-scan debounce for a volume hash. A new hash is only accepted once two consecutive scans agree on it, so a
 * bobbing hull that flips one boundary cell for a single scan never rebuilds the (expensive, client side)
 * water-occlusion mesh. Pure Java.
 */
public final class HoldDebounce {

    /** Consecutive identical scans needed before a changed hash is applied. */
    public static final int REQUIRED_SCANS = 2;

    private int appliedHash;
    private int pendingHash;
    private int pendingCount;

    public HoldDebounce(int initialHash) {
        this.appliedHash = initialHash;
        this.pendingHash = initialHash;
    }

    public int appliedHash() {
        return appliedHash;
    }

    /**
     * Feed the hash of the latest scan.
     *
     * @return true if the caller must now (re)apply the volume of this scan; the debounce then treats the hash as applied
     */
    public boolean offer(int hash) {
        if (hash == appliedHash) {
            pendingHash = hash;
            pendingCount = 0;
            return false;
        }
        if (hash == pendingHash && pendingCount > 0) {
            pendingCount++;
        } else {
            pendingHash = hash;
            pendingCount = 1;
        }
        if (pendingCount >= REQUIRED_SCANS) {
            appliedHash = hash;
            pendingCount = 0;
            return true;
        }
        return false;
    }
}

