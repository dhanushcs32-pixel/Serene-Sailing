package com.serenesailing.hold;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.water_occlusion.WaterOcclusionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Per-level (so per-side) registry of scanned sunken holds.
 *
 * <p>Ticked on BOTH the logical server and the logical client. Each ship has its own schedule
 * ({@link HoldScanSchedule}): its first scan is staggered, scans back off while nothing changes, and a block change wakes
 * it at once. At most one ship is scanned per tick, so the cost is spread instead of spiking. Each scan runs the
 * configured engine ({@link HoldScanMode}), then the two-scan {@link HoldDebounce} on the volume hash; when the volume
 * really changed, the Sable water-occlusion region ({@link SableWaterOcclusion}) and the envelope everything else reads
 * are swapped. Sable's regions are not networked, which is why both sides run their own registry.
 *
 * <p>Cost controls: ships are only scanned when they reach the sea band and are near a player; a scan that turns out
 * slow makes that ship scan less often; per-entity queries read a tiny snapshot of ACTIVE holds only. Reads
 * ({@link #isOccluded}, {@link #activeEnvelope}) are safe from other threads.
 */
public final class HoldRegistry {

    /** Ticks between scans of a ship that is still changing: row scanner (1 s), flood fill (2 s, it does more work). */
    public static final int SCAN_INTERVAL_TICKS = 20;
    public static final int FLOOD_SCAN_INTERVAL_TICKS = 40;
    /** Ticks between scans of a long-unchanged ship. Block changes wake it earlier. */
    public static final int MAX_SCAN_INTERVAL_TICKS = 200;
    public static final int MAX_FLOOD_SCAN_INTERVAL_TICKS = 100;
    /** A ship whose blocks changed is rescanned at once, but never more often than this. */
    public static final int DIRTY_MIN_SPACING_TICKS = 10;
    /** At most this many ships are scanned in one tick; the others wait a tick. */
    private static final int MAX_SCANS_PER_TICK = 1;
    /** A scan slower than this makes the ship scan less often (see {@link Entry#slowFactor}). */
    private static final long SLOW_SCAN_NANOS = 25_000_000L;
    /** Only sub-levels whose world AABB reaches into this band can hold sea water. */
    private static final double SEA_BAND_MIN_Y = 56.0;
    private static final double SEA_BAND_MAX_Y = 72.0;
    /** Only scan ships within this distance of a player (the client's own player, or any player on the server). */
    public static final double MAX_FOCUS_DISTANCE = 192.0;
    /** Ticks between clean-ups of vanished sub-levels. */
    private static final int CLEANUP_INTERVAL_TICKS = 20;
    /** Length of one performance statistics window (one minute). */
    private static final long STATS_WINDOW_TICKS = 1200;

    private static final Map<Level, HoldRegistry> INSTANCES = new ConcurrentHashMap<>();

    private static final ThreadLocal<Vector3d[]> SCRATCH =
            ThreadLocal.withInitial(() -> new Vector3d[]{new Vector3d(), new Vector3d()});

    private final Level level;
    private final Map<SubLevel, Entry> entries = new ConcurrentHashMap<>();
    /** Holds with an applied envelope. Replaced (never mutated) so per-entity queries can iterate it without locking. */
    private volatile Active[] actives = new Active[0];
    private long lastCleanupTick = Long.MIN_VALUE;

    // performance statistics: current window and the last finished one
    private long statWindowStart = Long.MIN_VALUE;
    private int statScans;
    private long statNanos, statMaxNanos;
    private int lastScans;
    private long lastNanos, lastMaxNanos;

    private HoldRegistry(Level level) {
        this.level = level;
    }

    private static final class Entry {
        HoldScanner.HullClass lastClass = HoldScanner.HullClass.FLAT_RAFT;
        HoldDebounce debounce = new HoldDebounce(0);
        HoldScanMode mode = HoldScanMode.ROW_SCANNER;
        /** Flood fill only: the previous analysis, for waterline hysteresis. */
        @Nullable HoldFlood.Result floodPrev;
        /** When to rescan this ship: staggered, backing off, reacting to block changes. */
        final HoldScanSchedule schedule = new HoldScanSchedule();
        @Nullable WaterOcclusionRegion region;
        /** The envelope currently applied (Sable region, wave mask, depth planes, purge, physics). */
        volatile @Nullable HoldScanner.Envelope active;
        /** The envelope of the most recent scan, applied or not (diagnostics). */
        volatile @Nullable HoldScanner.Envelope last;
        int lastHash;
        long lastScanNanos;
        /** 1 normally; doubled (up to 8) while this ship's scans are slow, which multiplies its scan intervals. */
        int slowFactor = 1;
        boolean warnedSlow;
    }

    private record Active(SubLevel sub, Entry entry) {
    }

    // =========================================================================================
    //  QUERIES
    // =========================================================================================

    /** Sub-levels currently registered for this level. Null if the level is not ticking holds. */
    @Nullable
    public static HoldRegistry peek(@Nullable Level level) {
        return level == null ? null : INSTANCES.get(level);
    }

    /** Drops a level's registry and its Sable regions (level unload / logout). */
    public static void discard(Level level) {
        final HoldRegistry registry = INSTANCES.remove(level);
        if (registry != null) {
            registry.clear();
        }
    }

    /**
     * Currently applied envelope for a sub-level, or null if none is applied (raft, dry dock, flat hull). Safe to call
     * from any thread (reads a volatile reference).
     */
    @Nullable
    public static HoldScanner.Envelope activeEnvelope(SubLevel sub) {
        if (sub == null) {
            return null;
        }
        final HoldRegistry registry = peek(sub.getLevel());
        if (registry == null) {
            return null;
        }
        final Entry entry = registry.entries.get(sub);
        return entry == null ? null : entry.active;
    }

    /**
     * Sable water-occlusion region currently registered for a sub-level, or null if none is registered.
     */
    @Nullable
    public static WaterOcclusionRegion activeRegion(SubLevel sub) {
        if (sub == null) {
            return null;
        }
        final HoldRegistry registry = peek(sub.getLevel());
        if (registry == null) {
            return null;
        }
        final Entry entry = registry.entries.get(sub);
        return entry == null ? null : entry.region;
    }

    /** Visits every sub-level with an applied sunken-hold envelope. */
    public void forEachActive(BiConsumer<SubLevel, HoldScanner.Envelope> visitor) {
        for (final Active a : actives) {
            final HoldScanner.Envelope env = a.entry().active;
            if (env != null) {
                visitor.accept(a.sub(), env);
            }
        }
    }

    /** True if this sub-level currently has a registered water-occlusion region. */
    public boolean hasRegion(SubLevel sub) {
        final Entry entry = entries.get(sub);
        return entry != null && entry.region != null;
    }

    public boolean hasActiveHolds() {
        return actives.length > 0;
    }

    /** Is any hold of this level currently registered? A very cheap early-out for the per-entity physics hooks. */
    public static boolean anyActive(Level level) {
        final HoldRegistry registry = level == null ? null : INSTANCES.get(level);
        return registry != null && registry.actives.length > 0;
    }

    private void rebuildActives() {
        final List<Active> list = new ArrayList<>();
        for (final Map.Entry<SubLevel, Entry> e : entries.entrySet()) {
            if (e.getValue().active != null) {
                list.add(new Active(e.getKey(), e.getValue()));
            }
        }
        actives = list.toArray(new Active[0]);
    }

    // =========================================================================================
    //  AIR POCKET / BILGE QUERIES
    // =========================================================================================

    /** Is this world position inside the sealed volume of any registered hold of the level? */
    public static boolean isOccluded(Level level, Vec3 worldPos) {
        final HoldRegistry registry = peek(level);
        return registry != null && registry.actives.length > 0 && worldPos != null
                && registry.contains(worldPos.x, worldPos.y, worldPos.z);
    }

    /** Same test for an entity: its feet position or its bounding-box centre. */
    public static boolean isEntityInDryHold(Level level, Vec3 feet, Vec3 boxCenter) {
        final HoldRegistry registry = peek(level);
        if (registry == null || registry.actives.length == 0) {
            return false;
        }
        return registry.contains(feet.x, feet.y, feet.z) || registry.contains(boxCenter.x, boxCenter.y, boxCenter.z);
    }

    private boolean contains(double wx, double wy, double wz) {
        Vector3d[] scratch = null;
        for (final Active a : actives) {
            final HoldScanner.Envelope env = a.entry().active;
            if (env == null) {
                continue;
            }
            final SubLevel sub = a.sub();
            final BoundingBox3dc box = sub.boundingBox();
            if (box == null || wx < box.minX() - 1.0 || wx > box.maxX() + 1.0
                    || wy < box.minY() - 1.0 || wy > box.maxY() + 1.0
                    || wz < box.minZ() - 1.0 || wz > box.maxZ() + 1.0) {
                continue;
            }
            if (scratch == null) {
                scratch = SCRATCH.get();
            }
            scratch[0].set(wx, wy, wz);
            sub.logicalPose().transformPositionInverse(scratch[0], scratch[1]);
            final int lx = (int) Math.floor(scratch[1].x);
            final int ly = (int) Math.floor(scratch[1].y);
            final int lz = (int) Math.floor(scratch[1].z);
            if (env.contains(lx, ly, lz)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPointInSunkenHold(SubLevel sub, double lx, double ly, double lz) {
        final HoldScanner.Envelope env = activeEnvelope(sub);
        if (env == null) {
            return false;
        }
        return env.contains((int) Math.floor(lx), (int) Math.floor(ly), (int) Math.floor(lz));
    }

    // =========================================================================================
    //  DIAGNOSTICS (client command /serenesailing holddebug)
    // =========================================================================================

    /** Human-readable state of every tracked ship, and why each one is (not) a hold. */
    public static List<String> describe(Level level, Vec3 world) {
        final List<String> out = new ArrayList<>();
        out.add("hold scan mode (server config): " + HoldConfig.scanMode());
        final HoldRegistry registry = peek(level);
        if (registry == null) {
            out.add("no hold registry for this level (no sub-levels scanned yet, or the level has no Sable container)");
            return out;
        }
        out.add(registry.perfSummary());
        final long now = level.getGameTime();
        int n = 0;
        final Vector3d in = new Vector3d(world.x, world.y, world.z);
        final Vector3d local = new Vector3d();
        for (final Map.Entry<SubLevel, Entry> e : registry.entries.entrySet()) {
            final Entry entry = e.getValue();
            final SubLevel sub = e.getKey();
            final HoldScanner.Envelope env = entry.active;
            final BoundingBox3dc box = sub.boundingBox();
            final boolean here = box != null && world.x >= box.minX() - 1 && world.x <= box.maxX() + 1
                    && world.y >= box.minY() - 1 && world.y <= box.maxY() + 1
                    && world.z >= box.minZ() - 1 && world.z <= box.maxZ() + 1;
            final StringBuilder sb = new StringBuilder("sub-level #").append(++n).append(here ? "  <== you are at this ship" : "")
                    .append(": lastScan=").append(entry.lastClass)
                    .append(" applied=").append(env != null && env.isSunken())
                    .append(" sableRegion=").append(entry.region != null)
                    .append(" | scan ").append(String.format("%.1f", entry.lastScanNanos / 1.0e6)).append(" ms, next in ")
                    .append(Math.max(0, entry.schedule.nextScanTick() - now)).append(" ticks (unchanged x")
                    .append(entry.schedule.stableScans()).append(entry.slowFactor > 1 ? ", slowed x" + entry.slowFactor : "").append(')');
            final HoldScanner.Envelope last = entry.last;
            if (last != null && last.stats() != null) {
                sb.append("\n    why: ").append(last.stats().describe());
            } else if (entry.floodPrev != null) {
                sb.append("\n    flood fill: dry cells=").append(entry.floodPrev.dryCells())
                        .append(" flooded=").append(entry.floodPrev.floodedCells())
                        .append(" openBelowWater=").append(entry.floodPrev.openCellsBelowWater());
            }
            if (env != null && here) {
                final Pose3dc pose = sub.logicalPose();
                pose.transformPositionInverse(in, local);
                final int lx = (int) Math.floor(local.x), ly = (int) Math.floor(local.y), lz = (int) Math.floor(local.z);
                final int row = lz - env.minZ();
                sb.append("\n    rows=").append(env.validRows())
                        .append(" localCell=(").append(lx).append(',').append(ly).append(',').append(lz).append(')');
                if (row >= 0 && row < env.depth()) {
                    sb.append(" rowSpan=").append(env.portX(row)).append("..").append(env.starboardX(row));
                }
                final boolean inColumn = env.columnLo(lx, lz) <= env.columnHi(lx, lz);
                sb.append(inColumn ? " columnY=" + env.columnLo(lx, lz) + ".." + env.columnHi(lx, lz) : " (no sealed column here)")
                        .append(" inside=").append(env.contains(lx, ly, lz));
                int water = 0;
                final List<String> samples = new ArrayList<>();
                for (int z = env.volumeMinZ(); z <= env.volumeMaxZ(); z++) {
                    for (int x = env.volumeMinX(); x <= env.volumeMaxX(); x++) {
                        for (int y = env.columnLo(x, z); y <= env.columnHi(x, z); y++) {
                            if (env.contains(x, y, z)) {
                                continue;
                            }
                            final net.minecraft.world.level.block.state.BlockState st =
                                    level.getBlockState(new BlockPos(x, y, z));
                            if (!st.getFluidState().isEmpty()) {
                                water++;
                                if (samples.size() < 4) {
                                    samples.add("(" + x + "," + y + "," + z + "):" + st.getBlock().getName().getString());
                                }
                            }
                        }
                    }
                }
                sb.append("\n    waterCellsInsideVolume=").append(water);
                if (!samples.isEmpty()) {
                    sb.append(" e.g. ").append(String.join("; ", samples));
                }
            }
            out.add(sb.toString());
        }
        if (n == 0) {
            out.add("registry exists but tracks no sub-levels near the sea band and a player");
        }
        return out;
    }

    private String perfSummary() {
        final int scans = lastScans > 0 || statScans == 0 ? lastScans : statScans;
        final long nanos = lastScans > 0 || statScans == 0 ? lastNanos : statNanos;
        final long max = lastScans > 0 || statScans == 0 ? lastMaxNanos : statMaxNanos;
        return String.format("hold scanning cost (%s side, %s window): %d scans, %.1f ms total, %.1f ms avg, %.1f ms worst; "
                        + "%d ships tracked, %d holds active",
                level.isClientSide ? "client" : "server", lastScans > 0 ? "last minute" : "current",
                scans, nanos / 1.0e6, scans == 0 ? 0.0 : nanos / 1.0e6 / scans, max / 1.0e6, entries.size(), actives.length);
    }

    private void recordScan(long now, long nanos) {
        if (statWindowStart == Long.MIN_VALUE || now < statWindowStart) {
            statWindowStart = now;
        }
        if (now - statWindowStart >= STATS_WINDOW_TICKS) {
            lastScans = statScans;
            lastNanos = statNanos;
            lastMaxNanos = statMaxNanos;
            statScans = 0;
            statNanos = 0;
            statMaxNanos = 0;
            statWindowStart = now;
        }
        statScans++;
        statNanos += nanos;
        statMaxNanos = Math.max(statMaxNanos, nanos);
    }

    // =========================================================================================
    //  TICK
    // =========================================================================================

    /**
     * Advances this level's scan schedules.
     *
     * @param level the level, server or client
     * @param focus client: the player's position (only nearby ships are scanned); server: null, which means "near any
     *              player of the level"
     */
    public static void tick(Level level, @Nullable Vec3 focus) {
        final SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        final HoldRegistry registry = INSTANCES.computeIfAbsent(level, HoldRegistry::new);
        registry.tickInternal(container, focus);
    }

    private void tickInternal(SubLevelContainer container, @Nullable Vec3 focus) {
        final long now = level.getGameTime();
        final HoldScanMode mode = HoldConfig.scanMode();
        final boolean flood = mode == HoldScanMode.FLOOD_FILL;
        final int base = flood ? FLOOD_SCAN_INTERVAL_TICKS : SCAN_INTERVAL_TICKS;
        final int max = flood ? MAX_FLOOD_SCAN_INTERVAL_TICKS : MAX_SCAN_INTERVAL_TICKS;

        final boolean cleanup = lastCleanupTick == Long.MIN_VALUE || now < lastCleanupTick
                || now - lastCleanupTick >= CLEANUP_INTERVAL_TICKS;
        if (cleanup) {
            lastCleanupTick = now;
        }
        final Set<SubLevel> seen = cleanup ? Collections.newSetFromMap(new IdentityHashMap<>()) : null;
        final Vec3[] foci = fociFor(focus);
        final Vector3d scratch = SCRATCH.get()[0];
        int scans = 0;

        for (final SubLevel sub : container.getAllSubLevels()) {
            if (level.isClientSide && sub instanceof ClientSubLevel client && !client.isFinalized()) {
                continue;
            }
            if (seen != null) {
                seen.add(sub);
            }
            Entry entry = entries.get(sub);
            if (!isNearSeaBand(sub, foci)) {
                // Left the sea band or every player's range: release the region, start afresh when it comes back.
                if (entry != null) {
                    entry.schedule.clearDirty();
                    if (entry.active != null || entry.region != null) {
                        release(entry);
                    }
                    entry.debounce = new HoldDebounce(0);
                    entry.lastClass = HoldScanner.HullClass.FLAT_RAFT;
                    entry.floodPrev = null;
                }
                continue;
            }
            if (entry == null) {
                entry = entries.computeIfAbsent(sub, k -> new Entry());
            }
            entry.schedule.initialise(now, base, System.identityHashCode(sub));
            if (scans >= MAX_SCANS_PER_TICK || !entry.schedule.due(now, DIRTY_MIN_SPACING_TICKS)) {
                continue; // not due, or another ship already took this tick's scan: it simply goes next tick
            }
            final boolean externalChange = entry.schedule.beginScan(now);
            final long t0 = System.nanoTime();
            scanOne(entry, sub, mode, scratch, externalChange, now, base * entry.slowFactor, max * entry.slowFactor);
            final long took = System.nanoTime() - t0;
            entry.lastScanNanos = took;
            recordScan(now, took);
            guardAgainstSlowScans(entry, took);
            scans++;
        }

        if (seen != null) {
            // Sub-levels that vanished (disassembled / unloaded): release their regions.
            boolean removed = false;
            for (final Iterator<Map.Entry<SubLevel, Entry>> it = entries.entrySet().iterator(); it.hasNext(); ) {
                final Map.Entry<SubLevel, Entry> e = it.next();
                if (!seen.contains(e.getKey())) {
                    release(e.getValue());
                    it.remove();
                    removed = true;
                }
            }
            if (removed) {
                rebuildActives();
            }
        }
    }

    /** A scan that took too long makes that ship scan less often, so one big hull can never eat the tick. */
    private void guardAgainstSlowScans(Entry entry, long nanos) {
        if (nanos > SLOW_SCAN_NANOS) {
            entry.slowFactor = Math.min(8, entry.slowFactor * 2);
            if (!entry.warnedSlow) {
                entry.warnedSlow = true;
                com.serenesailing.SereneSailing.LOGGER.info("[SereneSailing] A hold scan of one ship took {} ms ({} side, {}); "
                        + "that ship is scanned less often now. See /serenesailing holddebug.",
                        String.format("%.1f", nanos / 1.0e6), level.isClientSide ? "client" : "server", HoldConfig.scanMode());
            }
        } else if (nanos < SLOW_SCAN_NANOS / 3 && entry.slowFactor > 1) {
            entry.slowFactor--;
        }
    }

    /** Positions that make a ship worth scanning; null = no distance limit; empty = nobody around (scan nothing). */
    @Nullable
    private Vec3[] fociFor(@Nullable Vec3 focus) {
        if (focus != null) {
            return new Vec3[]{focus};
        }
        if (level.isClientSide) {
            return null;
        }
        final List<? extends Player> players = level.players();
        final Vec3[] foci = new Vec3[players.size()];
        for (int i = 0; i < foci.length; i++) {
            foci[i] = players.get(i).position();
        }
        return foci;
    }

    /** One scan of one ship with whichever engine is configured. */
    private void scanOne(Entry entry, SubLevel sub, HoldScanMode mode, Vector3d scratch, boolean externalChange,
                         long now, int base, int max) {
        if (entry.mode != mode) {
            // The engine was switched in the config: start this ship from scratch.
            release(entry);
            entry.debounce = new HoldDebounce(0);
            entry.lastClass = HoldScanner.HullClass.FLAT_RAFT;
            entry.floodPrev = null;
            entry.lastHash = 0;
            entry.mode = mode;
        }
        final HoldScanner.Envelope env;
        if (mode == HoldScanMode.FLOOD_FILL) {
            final HoldFloodBlocks.Outcome outcome =
                    HoldFloodBlocks.scan(level, sub, sub.logicalPose(), entry.floodPrev, scratch);
            if (outcome == null) {
                entry.schedule.defer(now, base); // cannot scan right now: keep whatever is applied
                return;
            }
            entry.floodPrev = outcome.result();
            env = outcome.envelope();
        } else {
            env = HoldBlocks.scan(level, sub, sub.logicalPose(), entry.lastClass, scratch);
            if (env == null) {
                entry.schedule.defer(now, base);
                return;
            }
        }
        entry.last = env;
        entry.lastClass = env.hullClass();
        final int hash = env.volumeHash();
        final boolean changed = hash != entry.lastHash;
        entry.lastHash = hash;
        final boolean applied = entry.debounce.offer(hash);
        if (applied) {
            apply(entry, sub, env);
        }
        entry.schedule.afterScan(now, applied, !applied && hash != entry.debounce.appliedHash(), externalChange,
                changed, base, max);
    }

    /**
     * A block next to this position changed (server block events and, on the client, a block-change hook). If it lies in a
     * ship's plot, that ship is rescanned soon (never more than once per {@link #DIRTY_MIN_SPACING_TICKS}). Cheap enough
     * to call for every block change.
     */
    public static void markDirty(Level level, BlockPos pos) {
        final HoldRegistry registry = INSTANCES.get(level);
        if (registry == null || registry.entries.isEmpty()) {
            return;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        final LevelPlot plot = container.getPlot(new ChunkPos(pos));
        final SubLevel sub = plot == null ? null : plot.getSubLevel();
        final Entry entry = sub == null ? null : registry.entries.get(sub);
        if (entry != null) {
            entry.schedule.markChanged();
        }
    }

    private void apply(Entry entry, SubLevel sub, HoldScanner.Envelope env) {
        SableWaterOcclusion.remove(level, entry.region);
        entry.region = null;
        if (env.isSunken()) {
            entry.region = SableWaterOcclusion.add(level, env);
            entry.active = env;
        } else {
            entry.active = null;
        }
        rebuildActives();
    }

    private void release(Entry entry) {
        SableWaterOcclusion.remove(level, entry.region);
        entry.region = null;
        entry.active = null;
        rebuildActives();
    }

    private void clear() {
        for (final Entry e : entries.values()) {
            SableWaterOcclusion.remove(level, e.region);
            e.region = null;
            e.active = null;
        }
        entries.clear();
        actives = new Active[0];
    }

    private static boolean isNearSeaBand(SubLevel sub, @Nullable Vec3[] foci) {
        final BoundingBox3dc box = sub.boundingBox();
        if (box == null || box.maxY() < SEA_BAND_MIN_Y || box.minY() > SEA_BAND_MAX_Y) {
            return false;
        }
        if (foci == null) {
            return true;
        }
        for (final Vec3 focus : foci) {
            final double dx = Math.max(Math.max(box.minX() - focus.x, focus.x - box.maxX()), 0.0);
            final double dz = Math.max(Math.max(box.minZ() - focus.z, focus.z - box.maxZ()), 0.0);
            if (dx * dx + dz * dz <= MAX_FOCUS_DISTANCE * MAX_FOCUS_DISTANCE) {
                return true;
            }
        }
        return false;
    }
}
